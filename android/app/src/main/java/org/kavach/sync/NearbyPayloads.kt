package org.kavach.sync

import org.kavach.core.Json

/** Keep Nearby BYTES payloads below the service limit, even when a phone has many pending reports. */
object NearbyPayloads {
    const val MAX_BYTES = 24 * 1024

    fun eventBatches(events: List<Map<String, Any?>>, maxBytes: Int = MAX_BYTES): List<ByteArray> {
        require(maxBytes > 0) { "maxBytes must be positive" }
        val result = ArrayList<ByteArray>()
        val batch = ArrayList<Map<String, Any?>>()
        fun encode(items: List<Map<String, Any?>>) = Json.canonical(items).toByteArray(Charsets.UTF_8)
        for (event in events) {
            val single = encode(listOf(event))
            require(single.size <= maxBytes) { "event is larger than a Nearby payload" }
            if (batch.isNotEmpty() && encode(batch + event).size > maxBytes) {
                result += encode(batch)
                batch.clear()
            }
            batch += event
        }
        if (batch.isNotEmpty()) result += encode(batch)
        return result
    }
}
