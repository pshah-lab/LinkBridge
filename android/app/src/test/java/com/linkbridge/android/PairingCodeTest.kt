package com.linkbridge.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same vector is checked on the Mac (macos/Sources/LinkBridgeCoreChecks) and was
 * computed independently with Python hashlib, so all three agree on the code.
 */
class PairingCodeTest {
    private val initiatorKey = byteArrayOf(4) + ByteArray(64) { (it + 1).toByte() }
    private val responderKey = byteArrayOf(4) + ByteArray(64) { (it + 101).toByte() }
    private val initiatorNonce = ByteArray(32) { 0xAA.toByte() }
    private val responderNonce = ByteArray(32) { 0x55 }

    @Test
    fun commitmentMatchesSharedVector() {
        val commitment = PairingCode.commitment(responderKey, responderNonce)
        assertEquals(
            "d55bcd8aa0adb263e70397ba70563b77202762669bc2a9fc8f03387ed2e8c0d9",
            commitment.joinToString("") { "%02x".format(it) }
        )
    }

    private val initiatorId = "00112233-4455-6677-8899-aabbccddeeff"
    private val responderId = "FFEEDDCC-BBAA-9988-7766-554433221100"

    @Test
    fun v3CodeAndSecretMatchSharedVectors() {
        assertEquals("00112233445566778899aabbccddeeff", PairingCode.uuidBytes(initiatorId).joinToString("") { "%02x".format(it) })
        assertEquals("169226", PairingCode.code(initiatorKey, responderKey, initiatorNonce, responderNonce, initiatorId, responderId))
        val secret = PairingCode.secret(ByteArray(32) { (it + 1).toByte() }, initiatorNonce, responderNonce, initiatorId, responderId)
        assertEquals("OZz4QmGDcBBB5/OUZlU/RFzGim/W7N18MKq9UM1A5Ow=", java.util.Base64.getEncoder().encodeToString(secret))
    }

    private val v4Secret = java.util.Base64.getDecoder().decode("OZz4QmGDcBBB5/OUZlU/RFzGim/W7N18MKq9UM1A5Ow=")

    private fun confirm(version: Int = 4, secret: ByteArray = v4Secret, initiator: String = initiatorId) =
        PairingCode.confirmation(secret, version, initiatorKey, responderKey, initiatorNonce, responderNonce, initiator, responderId)

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun v4ConfirmationMatchesSharedVector() {
        // Python hmac/hashlib; the same vector is checked on the Mac.
        assertEquals("3ee83e71775e875d51fca77a13b55422ec7df3741e17c90a681fe173784ce819", hex(confirm()))
        assertEquals(4, PairingCode.PAIRING_VERSION)
    }

    @Test
    fun validConfirmationAccepted() {
        assertTrue(PairingCode.isValidConfirmation(confirm(), confirm()))
        val reply = org.json.JSONObject().put("status", "paired").put("confirm", java.util.Base64.getEncoder().encodeToString(confirm()))
        assertTrue(PairingCode.isValidFinalizeReply(reply, confirm()))
    }

    @Test
    fun wrongOrMissingConfirmationRejected() {
        val expected = confirm()
        assertFalse("missing", PairingCode.isValidConfirmation(null, expected))
        assertFalse("empty", PairingCode.isValidConfirmation(ByteArray(0), expected))
        assertFalse("truncated", PairingCode.isValidConfirmation(expected.copyOf(16), expected))
        assertFalse("altered", PairingCode.isValidConfirmation(expected.copyOf().also { it[0] = (it[0].toInt() xor 1).toByte() }, expected))
        assertFalse("other secret (spoofed finalize)", PairingCode.isValidConfirmation(confirm(secret = ByteArray(32) { 7 }), expected))
        assertFalse("other device", PairingCode.isValidConfirmation(confirm(initiator = "11111111-2222-3333-4444-555555555555"), expected))
        assertFalse("no expected value", PairingCode.isValidConfirmation(expected, null))
        assertFalse("empty expected value", PairingCode.isValidConfirmation(ByteArray(0), ByteArray(0)))

        val plain = org.json.JSONObject().put("status", "paired")
        assertFalse("reply without confirm (any 2xx)", PairingCode.isValidFinalizeReply(plain, expected))
        assertFalse("reply with junk confirm", PairingCode.isValidFinalizeReply(org.json.JSONObject().put("confirm", "%%%"), expected))
    }

    @Test
    fun downgradedVersionRejected() {
        assertEquals("a357cc36ea72cebf35cbc20bb19484ea6dcfab6f40ffff64e2ed22ae01084a8d", hex(confirm(version = 3)))
        assertFalse(PairingCode.isValidConfirmation(confirm(version = 3), confirm()))
    }

    @Test
    fun existingPairingClassification() {
        val known = TrustedPeer("id", "Mac", "macos", "h", 1, "t", pairingKey = "KEY-A")
        assertEquals(ExistingPairing.NONE, ExistingPairing.of(null, "KEY-A"))
        assertEquals(ExistingPairing.SAME_PEER, ExistingPairing.of(known, "KEY-A"))
        assertEquals(ExistingPairing.KEY_CHANGED, ExistingPairing.of(known, "KEY-B"))
        assertEquals(ExistingPairing.SAME_PEER, ExistingPairing.of(known.copy(pairingKey = null), "KEY-B"))
    }

    @Test
    fun codeMatchesSharedVector() {
        assertEquals("894940", PairingCode.code(initiatorKey, responderKey, initiatorNonce, responderNonce))
    }

    @Test
    fun codeIsAlwaysSixDigits() {
        repeat(200) {
            val code = PairingCode.code(initiatorKey, responderKey, PairingCode.randomNonce(), PairingCode.randomNonce())
            assertEquals(6, code.length)
            assert(code.all { it.isDigit() })
        }
    }

    @Test
    fun displayGroupsDigits() {
        assertEquals("894 940", PairingCode.display("894940"))
    }
}
