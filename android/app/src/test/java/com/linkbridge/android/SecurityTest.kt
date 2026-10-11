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
        val records = decrypt(fromMac)
        assertEquals(listOf("{\"transferId\":\"x\"}", "hello from mac"), records.map { String(it) })
    }

    @Test
    fun roundTripsAcrossChunkBoundaries() {
        val plain = ByteArray(Security.CHUNK_SIZE * 2 + 12_345) { (it * 31).toByte() }
        val metadata = "{\"m\":1}".toByteArray()
        val out = ByteArrayOutputStream()
        val written = Security.encryptChunked(secret, ByteArrayInputStream(plain), out, metadata)
        assertEquals(out.size().toLong(), written)

        val records = decrypt(out.toByteArray())
        assertEquals(4, records.size)
        assertArrayEquals(metadata, records.first())
        assertArrayEquals(plain, records.drop(1).fold(ByteArray(0)) { acc, r -> acc + r })
    }

    @Test
    fun rejectsTamperedCiphertext() {
        val out = ByteArrayOutputStream()
        Security.encryptChunked(secret, ByteArrayInputStream(ByteArray(5000) { 7 }), out)
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
        Security.encryptChunked(secret, ByteArrayInputStream(ByteArray(5000) { 7 }), out)
        val truncated = out.toByteArray().copyOf(out.size() - 10)
        try {
            decrypt(truncated)
            fail("truncated ciphertext was accepted")
        } catch (expected: Exception) {
        }
    }

    @Test
    fun timingSafeEqualsComparesContent() {
        assertTrue(Security.timingSafeEquals("abc", "abc"))
        assertFalse(Security.timingSafeEquals("abc", "abd"))
        assertFalse(Security.timingSafeEquals("abc", "abcd"))
    }

    private fun decrypt(bytes: ByteArray): List<ByteArray> {
        val records = mutableListOf<ByteArray>()
        Security.decryptChunkedRecords(secret, ByteArrayInputStream(bytes), bytes.size.toLong()) { records += it }
        return records
    }
}
