package com.linkbridge.android

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Six-digit comparison code for pairing. Must match macos/.../PairingCode.swift.
 * The Mac commits to its nonce before seeing ours, so a man-in-the-middle
 * cannot grind keys to force both screens to show the same code.
 */
object PairingCode {
    fun commitment(publicKey: ByteArray, nonce: ByteArray): ByteArray =
        sha256("linkbridge-commit-v1".toByteArray(), publicKey, nonce)

    fun code(initiatorKey: ByteArray, responderKey: ByteArray, initiatorNonce: ByteArray, responderNonce: ByteArray): String {
        val digest = sha256("linkbridge-code-v1".toByteArray(), initiatorKey, responderKey, initiatorNonce, responderNonce)
        val value = digest.take(4).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
        return "%06d".format(value % 1_000_000)
    }

    /** Pairing protocol this build speaks; see the Mac's `PairingCode.pairingVersion`. Both sides refuse older. */
    const val PAIRING_VERSION = 4

    private val CONFIRM_LABEL = "linkbridge-confirm-v4".toByteArray()

    /**
     * Pairing v4: what the Mac's `/pair/finalize` reply must carry as `confirm`:
     * HMAC-SHA256(Kc, label || version || keys || nonces || ids), Kc = HKDF(secret, info = label).
     * Binds "paired" to this key exchange and version. It can't stop a MITM, who holds this
     * side's secret: the user confirming that the Mac lists this phone as paired does that.
     */
    fun confirmation(
        secret: ByteArray, version: Int, initiatorKey: ByteArray, responderKey: ByteArray,
        initiatorNonce: ByteArray, responderNonce: ByteArray, initiatorId: String, responderId: String
    ): ByteArray {
        require(version in 0..255)
        // An empty HKDF salt is HashLen zero bytes (RFC 5869); JCA refuses an empty HMAC key.
        val key = Hkdf.sha256(secret, ByteArray(32), CONFIRM_LABEL)
        val transcript = CONFIRM_LABEL + byteArrayOf(version.toByte()) + initiatorKey + responderKey +
            initiatorNonce + responderNonce + uuidBytes(initiatorId) + uuidBytes(responderId)
        return Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(transcript)
    }

    /** Constant-time check; a missing, empty or wrong-length value never matches. */
    fun isValidConfirmation(received: ByteArray?, expected: ByteArray?): Boolean =
        received != null && expected != null && expected.isNotEmpty() && MessageDigest.isEqual(received, expected)

    /** Checks the `confirm` field of a `/pair/finalize` reply. */
    fun isValidFinalizeReply(reply: org.json.JSONObject, expected: ByteArray?): Boolean {
        val received = reply.optString("confirm").takeIf { it.isNotBlank() }?.let {
            runCatching { java.util.Base64.getDecoder().decode(it) }.getOrNull()
        }
        return isValidConfirmation(received, expected)
    }

    /** Pairing v3: the code also covers both device IDs. */
    fun code(
        initiatorKey: ByteArray, responderKey: ByteArray, initiatorNonce: ByteArray, responderNonce: ByteArray,
        initiatorId: String, responderId: String
    ): String {
        val digest = sha256(
            "linkbridge-code-v2".toByteArray(), initiatorKey, responderKey, initiatorNonce, responderNonce,
            uuidBytes(initiatorId), uuidBytes(responderId)
        )
        val value = digest.take(4).fold(0L) { acc, byte -> (acc shl 8) or (byte.toLong() and 0xff) }
        return "%06d".format(value % 1_000_000)
    }

    /**
     * Pairing v3 secret: HKDF-SHA256 over the raw ECDH output, salted with both nonces and bound
     * to both device IDs, so re-pairing the same two devices gives a new secret.
     */
    fun secret(
        sharedSecret: ByteArray, initiatorNonce: ByteArray, responderNonce: ByteArray,
        initiatorId: String, responderId: String
    ): ByteArray = Hkdf.sha256(
        sharedSecret, initiatorNonce + responderNonce,
        "linkbridge-pair-v3".toByteArray() + uuidBytes(initiatorId) + uuidBytes(responderId)
    )

    /** The 16 bytes in RFC 4122 order, matching Swift's `UUID.bytes`. */
    fun uuidBytes(id: String): ByteArray {
        val uuid = UUID.fromString(id)
        return ByteBuffer.allocate(16).putLong(uuid.mostSignificantBits).putLong(uuid.leastSignificantBits).array()
    }

    fun randomNonce(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** "123456" -> "123 456" */
    fun display(code: String): String = if (code.length == 6) "${code.take(3)} ${code.takeLast(3)}" else code

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }
}

/** RFC 5869 HKDF with SHA-256, as CryptoKit's `HKDF<SHA256>.deriveKey`. */
object Hkdf {
    fun sha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int = 32): ByteArray {
        val prk = hmac(salt, ikm)
        val output = java.io.ByteArrayOutputStream()
        var block = ByteArray(0)
        var counter = 1
        while (output.size() < length) {
            block = hmac(prk, block + info + byteArrayOf(counter.toByte()))
            output.write(block)
            counter += 1
        }
        return output.toByteArray().copyOf(length)
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
