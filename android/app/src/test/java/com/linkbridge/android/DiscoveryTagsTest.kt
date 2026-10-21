package com.linkbridge.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class DiscoveryTagsTest {
    private val secret = Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
    private fun peer(id: String, token: String) = TrustedPeer(id, "Peer", "macos", "10.0.0.2", 49152, token)
    private fun randomSecret() = Base64.getEncoder().encodeToString(ByteArray(32).also(java.security.SecureRandom()::nextBytes))

    @Test
    fun tagMatchesTheSharedVector() {
        // Python hashlib/hmac; the same vector is in LinkBridgeCoreChecks.
        assertEquals("e715ba5b9a4483e8", DiscoveryTags.tag(secret, "000102030405060708090a0b0c0d0e0f"))
    }

    @Test
    fun onlyAPairedDeviceIsMatched() {
        val rid = DiscoveryTags.randomRid()
        assertEquals(32, rid.length)
        assertNotEquals(rid, DiscoveryTags.randomRid())
        val tags = DiscoveryTags.tags(listOf(randomSecret(), secret), rid)
        assertEquals("mine", DiscoveryTags.match(rid, tags, listOf(peer("other", randomSecret()), peer("mine", secret))))
        assertNull(DiscoveryTags.match(rid, tags, listOf(peer("other", randomSecret()))))
        assertNull(DiscoveryTags.match(DiscoveryTags.randomRid(), tags, listOf(peer("mine", secret))))
    }

    @Test
    fun tagsFitOneTxtValue() {
        val tags = DiscoveryTags.tags(List(30) { randomSecret() }, DiscoveryTags.randomRid())
        assertEquals(DiscoveryTags.MAX_TAGS, tags.split(',').size)
        assertTrue(("tags=$tags").toByteArray().size <= 255)
    }
}
