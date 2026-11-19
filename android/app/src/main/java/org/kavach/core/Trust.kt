package org.kavach.core

import java.time.OffsetDateTime

data class TrustEntry(
    val keyId: String,
    val publicKeyHex: String,
    val role: String,
    val expiresAt: String,
)

/** Offline trust registry: which device keys may sign which roles, and until when. */
class TrustRegistry(entries: List<TrustEntry>) {
    private val byId = entries.associateBy { it.keyId }

    /** Returns (ok, reason). The signed bytes are the canonical JSON of the body, as on the hub. */
    fun verify(body: Map<String, Any?>, signatureHex: String, keyId: String, requiredRole: String?,
               now: OffsetDateTime = OffsetDateTime.now()): Pair<Boolean, String> {
        val entry = byId[keyId] ?: return false to "unknown key id"
        if (now.isAfter(OffsetDateTime.parse(entry.expiresAt))) return false to "key expired"
        if (requiredRole != null && entry.role != requiredRole) {
            return false to "role ${entry.role} not permitted (need $requiredRole)"
        }
        val ok = try {
            Crypto.verify(Json.canonical(body).toByteArray(Charsets.UTF_8), Crypto.unhex(signatureHex),
                Crypto.unhex(entry.publicKeyHex))
        } catch (e: IllegalArgumentException) {
            false
        }
        return if (ok) true to "ok" else false to "bad signature"
    }
}
