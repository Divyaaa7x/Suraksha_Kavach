package org.kavach.service

import org.kavach.core.Json
import org.kavach.data.EventDao
import org.kavach.data.EventEntity
import org.kavach.graph.Approval
import org.kavach.graph.Engine
import org.kavach.graph.RouteResult
import org.kavach.graph.Report
import org.kavach.graph.Status
import org.kavach.graph.StatusView
import org.kavach.pack.Pack
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * On-device node. Mirrors server/kavach/service.py: the same validation rules, the same status view,
 * so a phone and the hub reach the same answer from the same signed events.
 */
class KavachNode(private val pack: Pack, private val dao: EventDao) {

    companion object {
        const val MAX_EVENT_BYTES = 4096
        val OBSERVATION_TYPES = setOf("WATER", "BLOCKAGE", "CLOSED", "OPEN", "UNKNOWN")
        val APPROVABLE = setOf(Status.VERIFIED_OPEN, Status.VERIFIED_CLOSED, Status.RESTRICTED)
        val SOURCE_CLASSES = setOf("FIELD", "OFFICIAL", "MODEL", "SIMULATED")
        val OBS_FIELDS = setOf("event_id", "kind", "asset_id", "observation_type", "observed_at", "position",
            "evidence_sha256", "source_class", "pack_version", "origin_key_id", "note")
        val APP_FIELDS = setOf("event_id", "kind", "asset_id", "approved_status", "approved_at", "rationale",
            "source_class", "pack_version", "origin_key_id")
        const val ROLE_SUPERVISOR = "supervisor"
    }

    val packVersion: String get() = pack.packVersion

    data class SubmitResult(val status: String, val eventId: String?, val reason: String? = null)

    /** Validate, verify and append one event. [raw] is the wire object including signature and key_id. */
    suspend fun submit(raw: Map<String, Any?>, receivedAt: String? = null, markSynced: Boolean = false): SubmitResult {
        val size = Json.canonical(raw).toByteArray(Charsets.UTF_8).size
        if (size > MAX_EVENT_BYTES) return reject("payload $size bytes exceeds $MAX_EVENT_BYTES")
        val signature = raw["signature"] as? String ?: return reject("missing field signature")
        val keyId = raw["key_id"] as? String ?: return reject("missing field key_id")
        val body = raw.filterKeys { it !in setOf("signature", "key_id", "received_at") }
        val eventId = body["event_id"] as? String ?: return reject("missing event_id")

        val err = when (body["kind"]) {
            "OBSERVATION" -> checkObservation(body)
            "APPROVAL" -> checkApproval(body)
            else -> "unknown kind ${body["kind"]}"
        }
        if (err != null) return reject(err)

        val asset = body["asset_id"] as? String ?: return reject("asset_id required")
        if (!pack.graph.crossings.containsKey(asset)) {
            return reject("asset $asset not in pack $packVersion")
        }
        val role = if (body["kind"] == "APPROVAL") ROLE_SUPERVISOR else null
        val (ok, msg) = pack.trust.verify(body, signature, keyId, role)
        if (!ok) return reject("signature check failed: $msg")

        val canonicalBody = Json.canonical(body)
        val existing = dao.byId(eventId)
        if (existing != null) {
            return if (existing.bodyJson == canonicalBody && existing.signature == signature) {
                SubmitResult("duplicate", eventId)
            } else {
                reject("event_id reused with different content")
            }
        }
        dao.insert(
            EventEntity(
                eventId = eventId,
                kind = body["kind"] as String,
                assetId = asset,
                bodyJson = canonicalBody,
                signature = signature,
                keyId = keyId,
                receivedAt = receivedAt ?: OffsetDateTime.now(ZoneOffset.UTC).toString(),
                synced = markSynced,
            )
        )
        return SubmitResult("accepted", eventId)
    }

    private fun reject(reason: String) = SubmitResult("rejected", null, reason)

    private fun checkObservation(b: Map<String, Any?>): String? {
        val extra = b.keys - OBS_FIELDS
        if (extra.isNotEmpty()) return "unexpected fields ${extra.sorted()}"
        if (b["observation_type"] !in OBSERVATION_TYPES) return "observation_type invalid"
        if (b["source_class"] !in SOURCE_CLASSES) return "source_class invalid"
        val pos = b["position"] as? Map<*, *> ?: return "position required"
        if (pos["lat"] !is Number || pos["lon"] !is Number) return "position required"
        if (b["evidence_sha256"] != null && b["evidence_sha256"] !is List<*>) return "evidence_sha256 must be a list"
        if (b["pack_version"] != packVersion) return "pack_version ${b["pack_version"]} does not match $packVersion"
        return null
    }

    private fun checkApproval(b: Map<String, Any?>): String? {
        val extra = b.keys - APP_FIELDS
        if (extra.isNotEmpty()) return "unexpected fields ${extra.sorted()}"
        if (b["approved_status"] !in APPROVABLE) return "approved_status must be VERIFIED_OPEN, VERIFIED_CLOSED or RESTRICTED"
        if ((b["rationale"] as? String).isNullOrEmpty()) return "rationale required for approval"
        if (b["source_class"] !in SOURCE_CLASSES) return "source_class invalid"
        if (b["pack_version"] != packVersion) return "pack_version ${b["pack_version"]} does not match $packVersion"
        val at = b["approved_at"] as? String ?: return "approved_at must be ISO 8601"
        if (runCatching { OffsetDateTime.parse(at) }.isFailure) return "approved_at must be ISO 8601"
        return null
    }

    // ---------------------------------------------------------------- views

    private suspend fun stored(): List<Pair<EventEntity, Map<String, Any?>>> = dao.all().map { e ->
        @Suppress("UNCHECKED_CAST")
        e to (Json.parse(e.bodyJson) as Map<String, Any?>)
    }

    suspend fun statuses(scenarioAtRisk: Set<String> = emptySet()): Map<String, StatusView> {
        val rows = stored()
        val reports = rows.filter { it.first.kind == "OBSERVATION" }.map {
            Report(it.first.eventId, it.first.assetId, it.second["observation_type"] as String, it.first.receivedAt)
        }
        val approvals = rows.filter { it.first.kind == "APPROVAL" }.map {
            Approval(
                eventId = it.first.eventId,
                assetId = it.first.assetId,
                approvedStatus = it.second["approved_status"] as String,
                approvedAt = it.second["approved_at"] as String,
                receivedAt = it.first.receivedAt,
                originKeyId = it.second["origin_key_id"] as String,
            )
        }
        val view = Engine.resolveStatus(pack.graph.crossings.keys, reports, approvals, OffsetDateTime.now(ZoneOffset.UTC))
        return view.mapValues { (c, v) ->
            if (c in scenarioAtRisk && v.status == Status.UNKNOWN)
                StatusView(Status.PREDICTED_RISK, false, "scenario overlay (SYNTHETIC)")
            else v
        }
    }

    private suspend fun statusMap(scenario: Set<String>) = statuses(scenario).mapValues { it.value.status }

    suspend fun route(origin: String, dest: String, vehicle: String, policy: String, scenario: Set<String>): RouteResult =
        Engine.planRoute(pack.graph, statusMap(scenario), origin, dest, vehicle, policy)

    suspend fun shortest(origin: String, dest: String) = Engine.shortestPath(pack.graph, origin, dest)

    suspend fun isolated(vehicle: String, policy: String, scenario: Set<String>) =
        Engine.isolated(pack.graph, statusMap(scenario), vehicle, policy)

    suspend fun inspection(scenario: Set<String>) = Engine.inspectionRanking(pack.graph, statusMap(scenario))

    /** Events not yet acknowledged by the hub or a peer, in wire form (body + signature + key_id). */
    suspend fun pendingWire(): List<Map<String, Any?>> = dao.unsynced().map { wire(it) }

    suspend fun allWire(): List<Map<String, Any?>> = dao.all().map { wire(it) }

    private fun wire(e: EventEntity): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        val body = Json.parse(e.bodyJson) as Map<String, Any?>
        return body + mapOf("signature" to e.signature, "key_id" to e.keyId, "received_at" to e.receivedAt)
    }

    suspend fun markSynced(ids: List<String>) {
        if (ids.isNotEmpty()) dao.markSynced(ids)
    }

    suspend fun counts(): Pair<Int, Int> {
        val all = dao.all()
        return all.count { it.kind == "OBSERVATION" } to all.count { it.kind == "APPROVAL" }
    }

    suspend fun eventIds(): List<String> = dao.ids()

    suspend fun allEvents(): List<EventEntity> = dao.all()
}
