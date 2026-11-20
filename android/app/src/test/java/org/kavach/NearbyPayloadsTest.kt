package org.kavach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kavach.core.Json
import org.kavach.sync.NearbyPayloads

class NearbyPayloadsTest {
    @Test
    fun batchesStayWithinLimitAndPreserveEveryEvent() {
        val events = (0 until 12).map { i -> mapOf("event_id" to "event-$i", "note" to "x".repeat(110)) }
        val batches = NearbyPayloads.eventBatches(events, maxBytes = 256)
        assertTrue(batches.size > 1)
        assertTrue(batches.all { it.size <= 256 })
        val ids = batches.flatMap { batch ->
            @Suppress("UNCHECKED_CAST")
            (Json.parse(String(batch, Charsets.UTF_8)) as List<Map<String, Any?>>).map { it["event_id"] as String }
        }
        assertEquals(events.map { it["event_id"] }, ids)
    }

    @Test(expected = IllegalArgumentException::class)
    fun refusesAnEventLargerThanOneNearbyPayload() {
        NearbyPayloads.eventBatches(listOf(mapOf("note" to "x".repeat(300))), maxBytes = 128)
    }

    @Test
    fun receivedEventsAreLimitedByByteAndItemCount() {
        val tooLarge = ByteArray(NearbyPayloads.MAX_BYTES + 1)
        try {
            NearbyPayloads.decodeEvents(tooLarge)
            throw AssertionError("oversized payload should be rejected before parsing")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("byte limit"))
        }

        val tooMany = Json.canonical((0..NearbyPayloads.MAX_EVENTS).map { mapOf("id" to it) })
            .toByteArray(Charsets.UTF_8)
        try {
            NearbyPayloads.decodeEvents(tooMany)
            throw AssertionError("too many events should be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("event limit"))
        }
    }

    @Test
    fun acknowledgementCannotMarkUnsentEventsAsSynced() {
        val ack = "ACK:[\"sent\",\"unsent\"]".toByteArray(Charsets.UTF_8)
        try {
            NearbyPayloads.acknowledgedEvents(ack, "ACK:", setOf("sent"))
            throw AssertionError("acknowledgement for an unsent event should be rejected")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("not sent"))
        }
    }

    @Test
    fun senderCapsBatchEventCountToReceiverLimit() {
        val events = (0..NearbyPayloads.MAX_EVENTS).map { mapOf("event_id" to "event-$it") }
        val batches = NearbyPayloads.eventBatches(events)
        assertEquals(2, batches.size)
        assertTrue(batches.all { NearbyPayloads.decodeEvents(it).size <= NearbyPayloads.MAX_EVENTS })
    }
}
