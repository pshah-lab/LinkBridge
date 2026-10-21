package com.linkbridge.android

import org.junit.Assert.assertEquals
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
