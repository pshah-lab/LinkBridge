package com.linkbridge.android.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class HotspotAddressTest {
    private fun ip(text: String) = InetAddress.getByName(text) as Inet4Address

    private fun nic(
        name: String,
        vararg addresses: String,
        up: Boolean = true,
        loopback: Boolean = false,
    ) = NetInterfaceInfo(name, up, loopback, addresses.map(::ip))

    private val station = listOf(ip("192.168.1.20"))

    @Test
    fun picksSoftApNotStation() {
        val interfaces = listOf(
            nic("lo", "127.0.0.1", loopback = true),
            nic("wlan0", "192.168.1.20"),
            nic("swlan0", "192.168.43.1"),
        )
        assertEquals(ip("192.168.43.1"), findHotspotAddress(interfaces, station))
    }

    @Test
    fun skipsStationAddressOnAMatchingInterface() {
        val interfaces = listOf(nic("wlan0", "192.168.1.20"), nic("wlan1", "192.168.49.1"))
        assertEquals(ip("192.168.49.1"), findHotspotAddress(interfaces, station))
    }

    @Test
    fun earliestPrefixWinsWhenSeveralQualify() {
        val interfaces = listOf(nic("wlan1", "192.168.49.1"), nic("ap0", "10.0.0.1"), nic("swlan0", "192.168.43.1"))
        assertEquals(ip("192.168.43.1"), findHotspotAddress(interfaces, emptyList()))
        assertEquals(ip("10.0.0.1"), findHotspotAddress(interfaces, emptyList(), prefixes = listOf("ap", "wlan")))
    }

    @Test
    fun nullWhenNoCandidate() {
        assertNull(findHotspotAddress(emptyList(), station))
        val interfaces = listOf(
            nic("lo", "127.0.0.1", loopback = true),
            nic("wlan0", "192.168.1.20"), // station
            nic("swlan0", "192.168.43.1", up = false), // down
            nic("swlan1", "8.8.8.8"), // not private
            nic("rmnet0", "10.5.5.5"), // private but not a Wi-Fi AP name
            nic("ap0"), // no IPv4
        )
        assertNull(findHotspotAddress(interfaces, station))
    }

    @Test
    fun ignoresLoopbackEvenWithAMatchingName() {
        assertNull(findHotspotAddress(listOf(nic("wlan9", "127.0.0.1", loopback = true)), emptyList()))
    }

    @Test
    fun unknownStationNeverFallsBackToBareWlan() {
        // Only the station interface is present: without a station address it must not be mistaken for the AP.
        val interfaces = listOf(nic("wlan0", "192.168.1.20"))
        assertNull(findHotspotAddress(interfaces, emptyList()))
        assertEquals(ip("192.168.43.1"), findHotspotAddress(interfaces + nic("swlan0", "192.168.43.1"), emptyList()))
    }

    @Test
    fun excludesEveryStationAddress() {
        val stations = listOf(ip("192.168.1.20"), ip("10.1.1.5"))
        val interfaces = listOf(nic("wlan0", "192.168.1.20"), nic("wlan1", "10.1.1.5"))
        assertNull(findHotspotAddress(interfaces, stations))
        assertEquals(ip("192.168.49.1"), findHotspotAddress(interfaces + nic("wlan2", "192.168.49.1"), stations))
    }
}
