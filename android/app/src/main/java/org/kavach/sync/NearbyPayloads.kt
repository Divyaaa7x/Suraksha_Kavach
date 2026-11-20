package org.kavach.sync

import org.kavach.core.Json

/** Keep Nearby BYTES payloads below the service limit, even when a phone has many pending reports. */
object NearbyPayloads {
    const val MAX_BYTES = 24 * 1024
    const val MAX_EVENTS = 100

    fun decodeEvents(bytes: ByteArray): List<Map<String, Any?>> {
        require(bytes.size <= MAX_BYTES) { "Nearby payload exceeds the byte limit" }
        val items = Json.parse(String(bytes, Charsets.UTF_8)) as? List<*>
            ?: throw IllegalArgumentException("Nearby event payload must be a list")
        require(items.size <= MAX_EVENTS) { "Nearby payload exceeds the event limit" }
        return items.map { item ->
            @Suppress("UNCHECKED_CAST")
            item as? Map<String, Any?> ?: throw IllegalArgumentException("Nearby event must be an object")
        }
    }

    fun decodeAcknowledgement(bytes: ByteArray, prefix: String): List<String> {
        require(bytes.size <= MAX_BYTES) { "Nearby acknowledgement exceeds the byte limit" }
        val text = String(bytes, Charsets.UTF_8)
        require(text.startsWith(prefix)) { "Nearby acknowledgement prefix is invalid" }
        val items = Json.parse(text.removePrefix(prefix)) as? List<*>
            ?: throw IllegalArgumentException("Nearby acknowledgement must be a list")
        require(items.size <= MAX_EVENTS) { "Nearby acknowledgement exceeds the event limit" }
        return items.map { it as? String ?: throw IllegalArgumentException("Nearby acknowledgement ID must be text") }
    }

    fun acknowledgedEvents(bytes: ByteArray, prefix: String, sentToPeer: Set<String>): List<String> {
        val ids = decodeAcknowledgement(bytes, prefix)
        require(sentToPeer.containsAll(ids)) { "acknowledgement contains an event not sent to this peer" }
        return ids
    }

    fun eventBatches(events: List<Map<String, Any?>>, maxBytes: Int = MAX_BYTES): List<ByteArray> {
        require(maxBytes > 0) { "maxBytes must be positive" }
        require(maxBytes <= MAX_BYTES) { "maxBytes cannot exceed the Nearby receive limit" }
        val result = ArrayList<ByteArray>()
        val batch = ArrayList<Map<String, Any?>>()
        fun encode(items: List<Map<String, Any?>>) = Json.canonical(items).toByteArray(Charsets.UTF_8)
        for (event in events) {
            val single = encode(listOf(event))
            require(single.size <= maxBytes) { "event is larger than a Nearby payload" }
            if (batch.isNotEmpty() && (batch.size >= MAX_EVENTS || encode(batch + event).size > maxBytes)) {
                result += encode(batch)
                batch.clear()
            }
            batch += event
        }
        if (batch.isNotEmpty()) result += encode(batch)
        return result
    }
}
