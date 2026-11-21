package com.linkbridge.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Same cases as the Mac "Protocol compatibility" checks. */
class ProtocolCompatTest {
    private val local = ProtocolLevel(2, 2)

    @Test
    fun localLevelIsTwoTwo() = assertEquals(local, ProtocolLevel.LOCAL)

    @Test
    fun compatibility() {
        assertEquals(Compatibility.PEER_TOO_OLD, ProtocolCompat.check(local, ProtocolLevel(1, 1)))
        assertEquals(Compatibility.LOCAL_TOO_OLD, ProtocolCompat.check(local, ProtocolLevel(3, 3)))
        assertEquals(Compatibility.OK, ProtocolCompat.check(local, ProtocolLevel(3, 2)))
        assertEquals(Compatibility.OK, ProtocolCompat.check(local, local))
    }

    @Test
    fun inference() {
        assertEquals(ProtocolLevel(2, 2), ProtocolLevel.fromTxt("2", null, hasTlsPort = true, hasDeviceId = false))
        assertEquals(ProtocolLevel(3, 2), ProtocolLevel.fromTxt("3", "2", hasTlsPort = true, hasDeviceId = false))
        assertEquals(ProtocolLevel(1, 1), ProtocolLevel.fromTxt(null, null, hasTlsPort = false, hasDeviceId = false))
        assertEquals(ProtocolLevel(2, 2), ProtocolLevel.fromTxt("abc", "", hasTlsPort = true, hasDeviceId = false))
        assertEquals(ProtocolLevel(2, 2), ProtocolLevel.fromJson(null, null))
        assertEquals(ProtocolLevel(4, 3), ProtocolLevel.fromJson(4, 3))
        assertEquals(ProtocolLevel(3, 2), ProtocolLevel.fromJson(3, null))
    }

    @Test
    fun messages() {
        assertEquals("Update LinkBridge on Pixel", ProtocolCompat.message(Compatibility.PEER_TOO_OLD, "Pixel"))
        assertEquals("Update LinkBridge on this device", ProtocolCompat.message(Compatibility.LOCAL_TOO_OLD, "Pixel"))
        assertNull(ProtocolCompat.message(Compatibility.OK, "Pixel"))
        assertEquals("Update LinkBridge on Pixel to receive its files.", ProtocolCompat.receiverNotice(Compatibility.PEER_TOO_OLD, "Pixel"))
        assertEquals("Update LinkBridge on this device to receive files from Pixel.", ProtocolCompat.receiverNotice(Compatibility.LOCAL_TOO_OLD, "Pixel"))
    }

    @Test
    fun refusals() {
        assertEquals(Compatibility.LOCAL_TOO_OLD, ProtocolCompat.rejection("""{"error":"update_required","update":"sender","protocol":2,"minProtocol":2}"""))
        assertEquals(Compatibility.PEER_TOO_OLD, ProtocolCompat.rejection("""{"error":"update_required","update":"receiver"}"""))
        assertNull(ProtocolCompat.rejection("""{"error":"tls_required"}"""))
        assertNull(ProtocolCompat.rejection("<html>"))
        assertEquals("""{"error":"update_required","update":"sender","protocol":2,"minProtocol":2}""",
            ProtocolCompat.rejectionBody(Compatibility.PEER_TOO_OLD))
        assertEquals("""{"error":"update_required","update":"receiver","protocol":2,"minProtocol":2}""",
            ProtocolCompat.rejectionBody(Compatibility.LOCAL_TOO_OLD))
    }
}
