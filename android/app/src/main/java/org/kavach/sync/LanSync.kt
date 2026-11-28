package org.kavach.sync

import org.kavach.core.Json
import org.kavach.data.OpsMission
import org.kavach.data.TaskDao
import org.kavach.data.TaskOutboxEntity
import org.kavach.service.KavachNode
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal fun isPrivateHubUrl(url: URL): Boolean {
    if (url.protocol !in setOf("http", "https") || url.userInfo != null || url.query != null || url.ref != null) return false
    if (url.port != -1 && url.port !in 1..65535) return false
    if (url.path.isNotEmpty() && url.path != "/") return false
    val host = url.host.lowercase()
    if (host == "localhost" || host == "10.0.2.2") return true
    if (host.any { it !in '0'..'9' && it != '.' }) return false
    val octets = host.split('.').map { it.toIntOrNull() ?: return false }
    if (octets.size != 4 || octets.any { it !in 0..255 }) return false
    return octets[0] == 10 ||
        (octets[0] == 192 && octets[1] == 168) ||
        (octets[0] == 172 && octets[1] in 16..31) ||
        octets[0] == 127
}

/**
 * Sync with the local command hub over the hotspot / router LAN. No internet is involved. The hub
 * URL must be a private address; network_security_config.xml enforces that for cleartext HTTP.
 */
class LanSync(private val node: KavachNode, private val taskDao: TaskDao) {

    data class Outcome(val pushed: Int, val rejected: Int, val pulled: Int, val message: String)

    suspend fun sync(baseUrl: String, hubToken: String = ""): Outcome {
        val base = URL(baseUrl.trimEnd('/'))
        require(isPrivateHubUrl(base)) { "hub must be an HTTP(S) service on a private LAN IPv4 address" }

        // Push: our unsynced events go to the hub, which validates each one.
        val pending = node.pendingWire()
        var pushed = 0
        var rejected = 0
        if (pending.isNotEmpty()) {
            val results = postJson(URL("$base/v1/sync/batch"), pending, hubToken) as List<*>
            val acked = ArrayList<String>()
            results.forEachIndexed { i, r ->
                @Suppress("UNCHECKED_CAST")
                val m = r as Map<String, Any?>
                when (m["status"]) {
                    "accepted", "duplicate" -> { acked.add(pending[i]["event_id"] as String); pushed++ }
                    else -> rejected++
                }
            }
            node.markSynced(acked)
        }

        // Pull: events the hub has (approvals, other devices' reports) that we do not hold yet.
        val hubEvents = getJson(URL("$base/v1/events"), hubToken) as List<*>
        var pulled = 0
        for (item in hubEvents) {
            @Suppress("UNCHECKED_CAST")
            val raw = item as Map<String, Any?>
            val res = node.submit(raw, receivedAt = raw["received_at"] as? String, markSynced = true)
            if (res.status == "accepted") pulled++
        }
        return Outcome(pushed, rejected, pulled, "pushed $pushed, pulled $pulled, rejected $rejected")
    }

    data class TaskSyncOutcome(val missions: Int, val uploaded: Int, val pending: Int)

    /** Pull assigned task projections and retry durable responder actions over the same private LAN. */
    suspend fun syncTasks(baseUrl: String, hubToken: String, responderId: String): TaskSyncOutcome {
        require(responderId.isNotBlank()) { "responder identity is required" }
        val base = URL(baseUrl.trimEnd('/'))
        require(isPrivateHubUrl(base)) { "hub must be an HTTP(S) service on a private LAN IPv4 address" }

        suspend fun pull(): List<OpsMission> {
            val raw = getJson(URL("$base/v1/ops/missions?responder_id=${pathSegment(responderId)}"), hubToken) as? List<*>
                ?: error("hub returned an invalid missions response")
            val missions = raw.map(OpsMission::parse)
            taskDao.upsertTasks(missions.map { it.entity() })
            return missions
        }

        pull()
        var uploaded = 0
        for (item in taskDao.pending()) {
            try {
                val payload = (Json.parse(item.payloadJson) as? Map<*, *>)
                    ?.entries?.associate { (k, v) -> (k as String) to v }
                    ?: error("queued action payload is invalid")
                val body = payload + ("idempotency_key" to item.idempotencyKey)
                val path = when {
                    item.operation.startsWith("receipt:") -> "/v1/ops/missions/${pathSegment(item.missionId)}/receipts"
                    item.operation.startsWith("transition:") -> "/v1/ops/missions/${pathSegment(item.missionId)}/transition"
                    item.operation == "observation" -> "/v1/ops/missions/${pathSegment(item.missionId)}/observations"
                    else -> error("unknown queued task operation ${item.operation}")
                }
                val response = postJson(URL("$base$path"), body, hubToken)
                val status = (response as? Map<*, *>)?.get("status") as? String
                if (status in setOf("rejected", "invalid", "error")) error("hub rejected task action")
                taskDao.markSynced(item.idempotencyKey)
                uploaded++
            } catch (e: Exception) {
                taskDao.markError(item.idempotencyKey, e.message ?: e.javaClass.simpleName)
            }
        }
        val missions = pull()
        return TaskSyncOutcome(missions.size, uploaded, taskDao.pending().size)
    }

    private fun pathSegment(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    private fun postJson(url: URL, payload: Any?, hubToken: String): Any? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/json")
            if (hubToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $hubToken")
        }
        conn.outputStream.use { it.write(Json.canonical(payload).toByteArray(Charsets.UTF_8)) }
        return readJson(conn)
    }

    private fun getJson(url: URL, hubToken: String): Any? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 10000
            if (hubToken.isNotBlank()) setRequestProperty("Authorization", "Bearer $hubToken")
        }
        return readJson(conn)
    }

    private fun readJson(conn: HttpURLConnection): Any? {
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        conn.disconnect()
        if (code !in 200..299) throw IllegalStateException("hub returned HTTP $code")
        return Json.parse(text)
    }
}
