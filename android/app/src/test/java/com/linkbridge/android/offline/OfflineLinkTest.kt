package com.linkbridge.android.offline

import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineLinkTest {
    private val vectors = JSONObject(File("../../docs/test-vectors/offline-link.json").readText())

    private fun expectedPayload(e: JSONObject): OfflineLinkPayload {
        val r = e.getJSONObject("record")
        return OfflineLinkPayload(
            ssid = e.getString("ssid"),
            passphrase = e.getString("pass"),
            address = InetAddress.getByName(e.getString("addr")) as Inet4Address,
            helloPort = e.getInt("hello"),
            record = DiscoveryRecord(
                rid = r.getString("rid"), tags = r.getString("tags"), deviceName = r.getString("deviceName"),
                platform = r.getString("platform"), protocol = r.getInt("protocol"), port = r.getInt("port"),
                tlsPort = if (r.has("tlsPort")) r.getInt("tlsPort") else null, features = r.getString("features"),
            ),
        )
    }

    private fun textOf(json: String) =
        "linkbridge-offline:1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    private fun json(
        addr: String = "\"192.168.1.1\"", hello: String = "49154", pass: String = "\"password1\"", ssid: String = "\"Net\"",
        record: String = RECORD,
    ) = """{"addr":$addr,"hello":$hello,"pass":$pass,"record":$record,"ssid":$ssid}"""

    @Test
    fun vectorsMatchSwift() {
        val valid = vectors.getJSONArray("valid")
        assertTrue(valid.length() > 0)
        for (i in 0 until valid.length()) {
            val v = valid.getJSONObject(i)
            val expected = expectedPayload(v.getJSONObject("expected"))
            val parsed = OfflineLinkPayload.parse(v.getString("text"))
            assertEquals(v.getString("note"), expected, parsed)
            assertEquals("encode: " + v.getString("note"), v.getString("text"), expected.encode())
        }
        val invalid = vectors.getJSONArray("invalid")
        assertTrue(invalid.length() > 0)
        for (i in 0 until invalid.length()) {
            val v = invalid.getJSONObject(i)
            assertNull(v.getString("reason"), OfflineLinkPayload.parse(v.getString("text")))
        }
    }

    @Test
    fun duplicateKeysAreInvalid() {
        assertNotNull(OfflineLinkPayload.parse(textOf(json())))
        assertNull(OfflineLinkPayload.parse(textOf(json().replace("{\"addr\"", "{\"hello\":1,\"addr\""))))
        assertNull(OfflineLinkPayload.parse(textOf(json(record = RECORD.replace("{", "{\"port\":1,")))))
    }

    @Test
    fun nullTlsPortIsAbsent() {
        val parsed = OfflineLinkPayload.parse(textOf(json(record = RECORD.replace("\"tlsPort\":49153", "\"tlsPort\":null"))))
        assertNotNull(parsed)
        assertNull(parsed!!.record.tlsPort)
    }

    @Test
    fun trailingGarbageAndNonIntegersAreInvalid() {
        assertNull(OfflineLinkPayload.parse(textOf(json() + "x")))
        assertNull(OfflineLinkPayload.parse(textOf(json(hello = "49154.0"))))
        assertNull(OfflineLinkPayload.parse(textOf(json(hello = "true"))))
        assertNull(OfflineLinkPayload.parse(textOf(json(pass = "12345678"))))
    }

    @Test
    fun recordRoundTripsAndRejectsBadInput() {
        val record = DiscoveryRecord("rid", "a,b", "Pixel", "android", 2, 49152, null, "file-transfer")
        assertEquals("""{"deviceName":"Pixel","features":"file-transfer","platform":"android","port":49152,"protocol":2,"rid":"rid","tags":"a,b"}""", record.toJson())
        assertEquals(record, DiscoveryRecord.fromJson(record.toJson()))
        assertEquals(record.copy(tlsPort = 1), DiscoveryRecord.fromJson(record.copy(tlsPort = 1).toJson()))
        assertNull(DiscoveryRecord.fromJson("not json"))
        assertNull(DiscoveryRecord.fromJson(RECORD.replace("{", "{\"extra\":1,")))
        assertNull(DiscoveryRecord.fromJson(RECORD.replace("\"tlsPort\":49153", "\"tlsPort\":0")))
    }

    private companion object {
        const val RECORD = """{"deviceName":"P","features":"file-transfer","platform":"android","port":49152,"protocol":2,"rid":"000102030405060708090a0b0c0d0e0f","tags":"","tlsPort":49153}"""
    }
}
