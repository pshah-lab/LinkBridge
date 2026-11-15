package com.linkbridge.android.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress

class HotspotBindingTest {
    private fun ip(text: String) = InetAddress.getByName(text) as Inet4Address

    private class Fixture {
        var filter: (InetAddress) -> Boolean = { true }
        val events = mutableListOf<String>()
        var interfaces: List<NetInterfaceInfo> = emptyList()
        val scheduled = mutableListOf<() -> Unit>()

        val binding = HotspotBinding(
            setAcceptFilter = { filter = it },
            abortReceives = { events += "abortReceives" },
            abortSends = { events += "abortSends" },
            interfaces = { interfaces },
            stationAddress = { null },
            schedule = { _, block ->
                scheduled += block
                Cancelable { scheduled -= block }
            },
        )
    }

    private val hotspotUp = listOf(NetInterfaceInfo("swlan0", true, false, listOf(ip("192.168.43.1"))))

    @Test
    fun activeAcceptsOnlyTheHotspotAddress() {
        val f = Fixture().apply { interfaces = hotspotUp }
        f.binding.onState(HotspotState.Active("DIRECT-lb", "secret123"))
        assertTrue(f.filter(ip("192.168.43.1")))
        assertFalse(f.filter(ip("192.168.1.20")))
    }

    @Test
    fun refusesEverythingUntilTheAddressAppears() {
        val f = Fixture()
        f.binding.onState(HotspotState.Active("DIRECT-lb", "secret123"))
        assertFalse(f.filter(ip("192.168.43.1")))
        f.interfaces = hotspotUp
        f.scheduled.single().invoke()
        assertTrue(f.filter(ip("192.168.43.1")))
    }

    @Test
    fun hotspotStopAbortsReceive() {
        val f = Fixture().apply { interfaces = hotspotUp }
        f.binding.onState(HotspotState.Active("DIRECT-lb", "secret123"))
        f.binding.onState(HotspotState.Stopped(bySystem = true))
        assertEquals(listOf("abortReceives", "abortSends"), f.events)
        assertTrue(f.filter(ip("192.168.1.20"))) // back to normal Wi-Fi operation
    }

    @Test
    fun stopCancelsAPendingAddressRetry() {
        val f = Fixture()
        f.binding.onState(HotspotState.Active("DIRECT-lb", "secret123"))
        f.binding.onState(HotspotState.Stopped(bySystem = false))
        assertTrue(f.scheduled.isEmpty())
    }

    @Test
    fun failedOrIdleDoesNotAbortAnything() {
        val f = Fixture()
        f.binding.onState(HotspotState.Failed(HotspotFailure.NO_CHANNEL))
        f.binding.onState(HotspotState.Idle)
        assertTrue(f.events.isEmpty())
    }
}
