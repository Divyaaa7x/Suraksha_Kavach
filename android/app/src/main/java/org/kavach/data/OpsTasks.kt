package org.kavach.data

import org.kavach.core.Json
import java.util.UUID

/** Narrow parser for the current hub projection; unknown fields remain harmless. */
data class OpsMission(
    val missionId: String,
    val crossingId: String,
    val squadId: String,
    val priority: String,
    val state: String,
    val warningId: String?,
    val createdAt: String,
    val updatedAt: String,
) {
    fun entity() = AssignedTaskEntity(missionId, crossingId, squadId, priority, state, warningId, createdAt, updatedAt)

    companion object {
        fun parse(value: Any?): OpsMission {
            val m = value as? Map<*, *> ?: error("mission must be an object")
            fun required(name: String) = m[name]?.toString() ?: error("mission missing $name")
            return OpsMission(
                required("mission_id"), required("crossing_id"), required("squad_id"),
                required("priority"), required("state"), m["warning_id"] as? String,
                required("created_at"), required("updated_at"),
            )
        }
    }
}

object OpsTaskAction {
    fun receipt(missionId: String, responderId: String, type: String, now: String): TaskOutboxEntity {
        require(type in setOf("DELIVERED", "READ", "ACKNOWLEDGED"))
        return queued(missionId, "receipt:$type", mapOf(
            "responder_id" to responderId, "receipt_type" to type,
        ), now)
    }

    fun transition(missionId: String, toState: String, responderId: String, now: String): TaskOutboxEntity {
        require(toState == "IN_PROGRESS")
        return queued(missionId, "transition:$toState", mapOf(
            "to_state" to toState, "actor_role" to "field_responder", "actor_id" to responderId,
        ), now)
    }

    fun observation(
        missionId: String, responderId: String, observedStatus: String, observedAt: String,
        notes: String, coordinates: Map<String, Any?>, signedEvent: Map<String, Any?>,
        queuedAt: String = observedAt,
    ): TaskOutboxEntity {
        require(observedStatus in setOf("OPEN", "CLOSED", "UNKNOWN"))
        require(notes.length <= 1000)
        val payload = linkedMapOf<String, Any?>(
            "observation_id" to (signedEvent["event_id"] as? String ?: error("signed observation event_id required")),
            "actor_id" to responderId, "observed_status" to observedStatus,
            "observed_at" to observedAt, "notes" to notes,
        )
        payload["coordinates"] = coordinates
        payload["signed_event"] = signedEvent
        return queued(missionId, "observation", payload, queuedAt)
    }

    private fun queued(missionId: String, operation: String, payload: Map<String, Any?>, now: String) =
        TaskOutboxEntity(UUID.randomUUID().toString(), missionId, operation, Json.canonical(payload), now)
}
