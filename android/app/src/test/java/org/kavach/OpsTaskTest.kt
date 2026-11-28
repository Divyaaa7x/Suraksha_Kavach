package org.kavach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kavach.core.Json
import org.kavach.data.OpsMission
import org.kavach.data.OpsTaskAction

class OpsTaskTest {
    @Test
    fun parsesHubMissionProjectionAndKeepsWarningProvenance() {
        val mission = OpsMission.parse(Json.parse("""{"mission_id":"m-1","crossing_id":"C-014","squad_id":"demo-squad","priority":"HIGH","state":"SENT","warning_id":"archive-7","created_at":"2026-11-28T10:00:00Z","updated_at":"2026-11-28T10:01:00Z"}"""))
        assertEquals("C-014", mission.crossingId)
        assertEquals("archive-7", mission.warningId)
        assertEquals("SENT", mission.state)
    }

    @Test
    fun responderReceiptsAreExplicitAndHaveIdempotencyKeys() {
        val delivered = OpsTaskAction.receipt("m-1", "responder-1", "DELIVERED", "2026-11-28T10:00:00Z")
        val ack = OpsTaskAction.receipt("m-1", "responder-1", "ACKNOWLEDGED", "2026-11-28T10:00:01Z")
        val body = Json.parse(delivered.payloadJson) as Map<*, *>
        assertEquals("DELIVERED", body["receipt_type"])
        assertEquals("responder-1", body["responder_id"])
        assertNotEquals(delivered.idempotencyKey, ack.idempotencyKey)
        assertTrue(delivered.idempotencyKey.isNotBlank())
    }

    @Test
    fun observationCarriesCoordinatesAndCannotClaimVerification() {
        val action = OpsTaskAction.observation(
            "m-1", "responder-1", "CLOSED", "2026-11-28T10:02:00Z", "water over deck",
            mapOf("lat" to 18.0, "lon" to 78.0, "accuracy_m" to 8.0),
            mapOf("event_id" to "event-1", "signature" to "test-signature", "key_id" to "demo-responder-01"),
        )
        val body = Json.parse(action.payloadJson) as Map<*, *>
        assertEquals("CLOSED", body["observed_status"])
        assertEquals("water over deck", body["notes"])
        assertTrue(body["coordinates"] is Map<*, *>)
        assertEquals("event-1", (body["signed_event"] as Map<*, *>)["event_id"])
        assertEquals("observation", action.operation)
    }
}
