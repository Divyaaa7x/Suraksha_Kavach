package org.kavach.core

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer
import java.security.MessageDigest

/** Ed25519 and SHA-256 helpers (Bouncy Castle works on every API level from 26 up). */
object Crypto {

    fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    fun sha256Hex(bytes: ByteArray): String = hex(sha256(bytes))

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun unhex(s: String): ByteArray {
        require(s.length % 2 == 0) { "odd hex length" }
        return ByteArray(s.length / 2) { i -> s.substring(2 * i, 2 * i + 2).toInt(16).toByte() }
    }

    fun publicKeyFromSeed(seed: ByteArray): ByteArray =
        Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun sign(message: ByteArray, seed: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun verify(message: ByteArray, signature: ByteArray, publicKey: ByteArray): Boolean {
        return try {
            val verifier = Ed25519Signer()
            verifier.init(false, Ed25519PublicKeyParameters(publicKey, 0))
            verifier.update(message, 0, message.size)
            verifier.verifySignature(signature)
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    /** SYNTHETIC DEMO seeds. They match server/kavach/pack.py so the phone and hub share test keys. */
    fun demoSeed(label: String): ByteArray = sha256(label.toByteArray(Charsets.UTF_8))

    const val PACK_SIGNER_LABEL = "SYNTHETIC DEMO ONLY - pack signer - not for production"
    const val RESPONDER_LABEL = "SYNTHETIC DEMO ONLY - responder 01"
    const val SUPERVISOR_LABEL = "SYNTHETIC DEMO ONLY - supervisor 01"
}
