package org.kavach

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kavach.core.Crypto
import org.kavach.core.Json

/**
 * Cross-language vector. The expected canonical text and signature were produced by the Python hub
 * (server/kavach/signing.py canonical_bytes + pack.py demo responder key). If this test fails, phone
 * and hub will disagree about every signature, so do not "fix" the test without checking Python first.
 */
class JsonAndCryptoGoldenTest {

    private val body: Map<String, Any?> = linkedMapOf(
        "event_id" to "vec-1",
        "kind" to "OBSERVATION",
        "asset_id" to "C1",
        "observation_type" to "OPEN",
        "observed_at" to "2026-11-19T10:00:00+00:00",
        "position" to linkedMapOf("lat" to 18.05, "lon" to 78.26, "accuracy_m" to 12.5),
        "evidence_sha256" to emptyList<String>(),
        "source_class" to "SIMULATED",
        "pack_version" to "DEMO-SYN-001",
        "origin_key_id" to "demo-responder-01",
        "note" to "naïve \"quote\" tab\there",
    )

    private val expectedCanonical =
        "{\"asset_id\":\"C1\",\"event_id\":\"vec-1\",\"evidence_sha256\":[],\"kind\":\"OBSERVATION\"," +
            "\"note\":\"naïve \\\"quote\\\" tab\\there\",\"observation_type\":\"OPEN\"," +
            "\"observed_at\":\"2026-11-19T10:00:00+00:00\",\"origin_key_id\":\"demo-responder-01\"," +
            "\"pack_version\":\"DEMO-SYN-001\",\"position\":{\"accuracy_m\":12.5,\"lat\":18.05,\"lon\":78.26}," +
            "\"source_class\":\"SIMULATED\"}"

    private val expectedSignature =
        "f69e1437b3ab991568972cdb360bb51ba41b4c04f2bdf53bd691d178cc3a8fe0fb9afae5830819a6e2fe279dbf95b5eb00bd21ed2f23f52e79d223fc43929306"

    @Test
    fun canonicalMatchesPython() {
        assertEquals(expectedCanonical, Json.canonical(body))
    }

    @Test
    fun signatureMatchesPython() {
        val seed = Crypto.demoSeed(Crypto.RESPONDER_LABEL)
        val sig = Crypto.sign(Json.canonical(body).toByteArray(Charsets.UTF_8), seed)
        assertEquals(expectedSignature, Crypto.hex(sig))
        val pub = Crypto.publicKeyFromSeed(seed)
        assertTrue(Crypto.verify(Json.canonical(body).toByteArray(Charsets.UTF_8), sig, pub))
    }

    @Test
    fun parseThenCanonicalIsStable() {
        val once = Json.canonical(Json.parse(expectedCanonical))
        assertEquals(expectedCanonical, once)
    }

    @Test
    fun tamperedBodyFailsVerification() {
        val seed = Crypto.demoSeed(Crypto.RESPONDER_LABEL)
        val sig = Crypto.sign(Json.canonical(body).toByteArray(Charsets.UTF_8), seed)
        val changed = body + mapOf("observation_type" to "CLOSED")
        val pub = Crypto.publicKeyFromSeed(seed)
        assertFalse(Crypto.verify(Json.canonical(changed).toByteArray(Charsets.UTF_8), sig, pub))
    }
}
