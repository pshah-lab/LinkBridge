package com.linkbridge.android

import java.security.MessageDigest
import java.security.SecureRandom

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

    fun randomNonce(): ByteArray = ByteArray(32).also { SecureRandom().nextBytes(it) }

    /** "123456" -> "123 456" */
    fun display(code: String): String = if (code.length == 6) "${code.take(3)} ${code.takeLast(3)}" else code

    private fun sha256(vararg parts: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach { digest.update(it) }
        return digest.digest()
    }
}
