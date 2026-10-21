package com.linkbridge.android

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Bonjour TXT without a stable device ID. Must match the Mac's `DiscoveryTags`: a random `rid`
 * per launch, and per paired device the first 8 bytes of HMAC(K, rid) with
 * K = HKDF-SHA256(secret, salt "linkbridge-discovery-v1", info "tag"). Only a paired device can
 * find its tag; to everyone else the values are random and change every launch.
 */
object DiscoveryTags {
    /** A TXT value is at most 255 bytes: "tags=" plus 14 × 17 characters fits. */
    const val MAX_TAGS = 14

    fun randomRid(): String = ByteArray(16).also(SecureRandom()::nextBytes).toHex()

    fun tag(secret: String, rid: String): String? = runCatching {
        val key = Hkdf.sha256(Base64.getDecoder().decode(secret), "linkbridge-discovery-v1".toByteArray(), "tag".toByteArray())
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }
        mac.doFinal(rid.hexToBytes()).copyOf(8).toHex()
    }.getOrNull()

    fun tags(secrets: List<String>, rid: String): String =
        secrets.take(MAX_TAGS).mapNotNull { tag(it, rid) }.joinToString(",")

    /** The device ID of the paired peer whose tag is in [tags], if any. */
    fun match(rid: String, tags: String, peers: List<TrustedPeer>): String? {
        val published = tags.split(',').filter { it.isNotEmpty() }.toSet()
        if (published.isEmpty()) return null
        return peers.firstOrNull { peer -> peer.authToken?.let { tag(it, rid) } in published }?.deviceId
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private fun String.hexToBytes(): ByteArray {
        require(length % 2 == 0) { "Odd hex length" }
        return ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
