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
}
