package org.kavach.core

import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Builds signed wire events on the device. The phone holds a SYNTHETIC DEMO responder key here.
 * A real device must keep its private key in Android Keystore (dossier section 43); that is a TODO.
 */
object Signed {
    const val RESPONDER_KEY_ID = "demo-responder-01"

    private val responderSeed: ByteArray by lazy { Crypto.demoSeed(Crypto.RESPONDER_LABEL) }

    fun observation(
        assetId: String,
        observationType: String,
        lat: Double,
        lon: Double,
        accuracyM: Double,
        note: String,
        packVersion: String,
        sourceClass: String = "SIMULATED",
    ): Map<String, Any?> {
        val body = linkedMapOf<String, Any?>(
            "event_id" to UUID.randomUUID().toString(),
            "kind" to "OBSERVATION",
            "asset_id" to assetId,
            "observation_type" to observationType,
            "observed_at" to OffsetDateTime.now(ZoneOffset.UTC).toString(),
            "position" to linkedMapOf<String, Any?>("lat" to lat, "lon" to lon, "accuracy_m" to accuracyM),
            "evidence_sha256" to emptyList<String>(),
            "source_class" to sourceClass,
            "pack_version" to packVersion,
            "origin_key_id" to RESPONDER_KEY_ID,
            "note" to note,
        )
        return withSignature(body, RESPONDER_KEY_ID, responderSeed)
    }

    /** Add the signature and key id to a body. The signature covers the canonical body only. */
    fun withSignature(body: Map<String, Any?>, keyId: String, seed: ByteArray): Map<String, Any?> {
        val sig = Crypto.sign(Json.canonical(body).toByteArray(Charsets.UTF_8), seed)
        return body + mapOf("signature" to Crypto.hex(sig), "key_id" to keyId)
    }
}
