package org.kavach.sync

import org.kavach.core.Json
import org.kavach.service.KavachNode
import java.net.HttpURLConnection
import java.net.URL

/**
 * Sync with the local command hub over the hotspot / router LAN. No internet is involved. The hub
 * URL must be a private address; network_security_config.xml enforces that for cleartext HTTP.
 */
class LanSync(private val node: KavachNode) {

    data class Outcome(val pushed: Int, val rejected: Int, val pulled: Int, val message: String)

    private fun isPrivate(url: URL): Boolean {
        val h = url.host
        return h == "localhost" || h == "10.0.2.2" || h.startsWith("192.168.") || h.startsWith("10.") ||
            Regex("""172\.(1[6-9]|2\d|3[01])\..*""").matches(h)
    }

    suspend fun sync(baseUrl: String): Outcome {
        val base = URL(baseUrl.trimEnd('/'))
        require(isPrivate(base)) { "hub must be on a private LAN address" }

        // Push: our unsynced events go to the hub, which validates each one.
        val pending = node.pendingWire()
        var pushed = 0
        var rejected = 0
        if (pending.isNotEmpty()) {
            val results = postJson(URL("$base/v1/sync/batch"), pending) as List<*>
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
        val hubEvents = getJson(URL("$base/v1/events")) as List<*>
        var pulled = 0
        for (item in hubEvents) {
            @Suppress("UNCHECKED_CAST")
            val raw = item as Map<String, Any?>
            val res = node.submit(raw, receivedAt = raw["received_at"] as? String, markSynced = true)
            if (res.status == "accepted") pulled++
        }
        return Outcome(pushed, rejected, pulled, "pushed $pushed, pulled $pulled, rejected $rejected")
    }

    private fun postJson(url: URL, payload: Any?): Any? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/json")
        }
        conn.outputStream.use { it.write(Json.canonical(payload).toByteArray(Charsets.UTF_8)) }
        return readJson(conn)
    }

    private fun getJson(url: URL): Any? {
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = 5000
            readTimeout = 10000
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
