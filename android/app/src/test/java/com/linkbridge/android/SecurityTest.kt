package com.linkbridge.android

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64

class SecurityTest {
    private val transferId = "00112233-4455-6677-8899-aabbccddeeff"
    // 32 bytes 0x01..0x20; the Mac vectors below were produced with this secret.
    private val secret = "AQIDBAUGBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyA="

    @Test
    fun hmacMatchesMacAndPython() {
        val message = "POST\n/transfer/upload\ndevice\n1700000000000\ntransfer\naes-gcm-chunked-v1\n42"
        assertEquals("SK4SJ4PsdVIZOd5OXKbFVmo5N+sdR2g3j9HRtxzoeaU=", Security.hmacSha256Base64(secret, message))
    }

    @Test
    fun decryptsCiphertextProducedByTheMac() {
        // Security.encryptChunkedFile on macOS: metadata record, then "hello from mac".
        val fromMac = Base64.getDecoder().decode(
            "1dd+SKmnR0y7uvMJAAAAIrW6p8/LtHvHvUbWscS3z7mDvbSOY0/e2Wbr8aWbt8iI6NIRpNL1L8C2Ilenz4IAAAAepuOssvZPPfQ27E3gazfY9hw8yYt4Rf79rrFfsqV6"
        )
        val records = mutableListOf<ByteArray>()
        Security.decryptChunkedRecords(secret, ByteArrayInputStream(fromMac), fromMac.size.toLong(),
            Security.LEGACY_ENCRYPTION_SCHEME, transferId) { records += it }
        assertEquals(listOf("{\"transferId\":\"x\"}", "hello from mac"), records.map { String(it) })
    }

    @Test
    fun roundTripsAcrossChunkBoundaries() {
        val plain = ByteArray(Security.CHUNK_SIZE * 2 + 12_345) { (it * 31).toByte() }
        val metadata = "{\"m\":1}".toByteArray()
        val out = ByteArrayOutputStream()
        val written = Security.encryptChunked(secret, ByteArrayInputStream(plain), out, metadata, transferId)
        assertEquals(out.size().toLong(), written)

        val records = decrypt(out.toByteArray())
        assertEquals(4, records.size)
        assertArrayEquals(metadata, records.first())
        assertArrayEquals(plain, records.drop(1).fold(ByteArray(0)) { acc, r -> acc + r })
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val out = ByteArrayOutputStream()
        Security.encryptChunked(secret, ByteArrayInputStream(ByteArray(5000) { 7 }), out, transferId = transferId)
        val tampered = out.toByteArray().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 1).toByte() }
        try {
            decrypt(tampered)
            fail("tampered ciphertext was accepted")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun rejectsTruncatedCiphertext() {
        val out = ByteArrayOutputStream()
        Security.encryptChunked(secret, ByteArrayInputStream(ByteArray(5000) { 7 }), out, transferId = transferId)
        val truncated = out.toByteArray().copyOf(out.size() - 10)
        try {
            decrypt(truncated)
            fail("truncated ciphertext was accepted")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun v2KeysAndRecordMatchTheMac() {
        // Bytes 0x01..0x20; HKDF vectors from Python, record vector from LinkBridgeCoreChecks.
        val vectorSecret = java.util.Base64.getEncoder().encodeToString(ByteArray(32) { (it + 1).toByte() })
        val (enc, mac) = Security.keys(vectorSecret, Security.ENCRYPTION_SCHEME)
        assertEquals("6c287fccda61344df05fcd9bd3fabecb95ec350bfae04148f8ed849ef48ac1a7", enc.hex())
        assertEquals("7e38b8fd173a295c5acf146530b517087bf70f64f50b04dc9c8e8534da4d4c3d", mac.hex())
        val record = Security.record("hello".toByteArray(), javax.crypto.spec.SecretKeySpec(enc, "AES"), ByteArray(12) { 9 },
            "00112233-4455-6677-8899-aabbccddeeff", 1, last = true)
        assertEquals("0909090909090909090909098000001574bb34452aad018c207bab7ab1c62197bce9d0cad6", record.hex())
    }

    @Test
    fun v2RejectsReorderedDroppedSplicedAndCutOffRecords() {
        val key = javax.crypto.spec.SecretKeySpec(Security.keys(secret, Security.ENCRYPTION_SCHEME).first, "AES")
        fun records(id: String, vararg items: Pair<String, Boolean>) = items.mapIndexed { index, (text, last) ->
            Security.record(text.toByteArray(), key, ByteArray(12) { index.toByte() }, id, index.toLong(), last)
        }
        val r = records(transferId, "meta" to false, "a" to false, "b" to true)
        fun accepted(parts: List<ByteArray>) = runCatching { decrypt(parts.fold(ByteArray(0)) { acc, p -> acc + p }) }.isSuccess
        assertTrue(accepted(r))
        assertFalse("reordered", accepted(listOf(r[0], r[2], r[1])))
        assertFalse("dropped", accepted(listOf(r[0], r[2])))
        assertFalse("cut off", accepted(listOf(r[0], r[1])))
        assertFalse("after last", accepted(r + r[2]))
        val other = records("ffeeddcc-bbaa-9988-7766-554433221100", "x" to false, "y" to false)
        assertFalse("spliced", accepted(listOf(r[0], other[1], r[2])))
    }

    @Test
    fun v2SignsWithItsOwnKey() {
        assertFalse(Security.uploadSignature(secret, Security.ENCRYPTION_SCHEME, "m") ==
            Security.uploadSignature(secret, Security.LEGACY_ENCRYPTION_SCHEME, "m"))
    }

    private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }

    @Test
    fun timingSafeEqualsComparesContent() {
        assertTrue(Security.timingSafeEquals("abc", "abc"))
        assertFalse(Security.timingSafeEquals("abc", "abd"))
        assertFalse(Security.timingSafeEquals("abc", "abcd"))
    }

    private fun decrypt(bytes: ByteArray): List<ByteArray> {
        val records = mutableListOf<ByteArray>()
        Security.decryptChunkedRecords(secret, ByteArrayInputStream(bytes), bytes.size.toLong(), transferId = transferId) { records += it }
        return records
    }
}
