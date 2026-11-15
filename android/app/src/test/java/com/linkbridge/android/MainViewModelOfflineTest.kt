package com.linkbridge.android

import com.linkbridge.android.LinkBridgeHub.SendState
import com.linkbridge.android.offline.DiscoveryRecord
import com.linkbridge.android.offline.HotspotFailure
import com.linkbridge.android.offline.HotspotState
import com.linkbridge.android.offline.OfflineLinkPayload
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.presentation.ReceiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.Inet4Address
import java.net.InetAddress
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelOfflineTest {
    private val dispatcher = StandardTestDispatcher()
    private val hub = FakeHub()
    private var now = 1_000_000L

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    /** Returns the hotspot to Idle at the end: the countdown ticker stops only then, and runTest would never finish. */
    private fun offlineTest(body: suspend TestScope.() -> Unit) = runTest(dispatcher) {
        try {
            body()
        } finally {
            hub.hotspotState.value = HotspotState.Idle
            runCurrent()
        }
    }

    // The countdown ticks forever while Active, so these tests use runCurrent, never advanceUntilIdle.
    private fun TestScope.viewModel(sdkInt: Int = 34): MainViewModel =
        MainViewModel(
            hub, FakeStores(), FakePairing(), FakePrefs(), CoroutineScope(SupervisorJob() + dispatcher), dispatcher,
            clock = { now }, zone = ZoneOffset.UTC, sdkInt = sdkInt
        ).also { runCurrent() }

    private val address = InetAddress.getByName("192.168.43.1") as Inet4Address
    private val record = DiscoveryRecord(
        rid = "r1", tags = "", deviceName = "Pixel", platform = "android", protocol = 1, port = 52000,
        tlsPort = 52000, features = "files",
    )

    @Test
    fun cardShowsAfterFiveSecondsWithNoPeers() = offlineTest {
        val vm = viewModel()
        assertFalse(vm.offline.value.cardVisible)

        advanceTimeBy(4_999)
        assertFalse(vm.offline.value.cardVisible)

        advanceTimeBy(2)
        val ui = vm.offline.value
        assertTrue(ui.cardVisible)
        assertEquals("No Wi-Fi? Connect directly", ui.cardText)
        assertTrue(ui.cardEnabled)

        hub.peers.value = listOf(unpairedMac)
        runCurrent()
        assertFalse(vm.offline.value.cardVisible)
    }

    @Test
    fun cardSaysNeedsAndroid13OnApi32() = offlineTest {
        val vm = viewModel(sdkInt = 32)
        advanceTimeBy(5_001)

        val ui = vm.offline.value
        assertTrue(ui.cardVisible)
        assertEquals("Needs Android 13 or later", ui.cardText)
        assertFalse(ui.cardEnabled)

        vm.startOffline()
        runCurrent()
        assertEquals(0, hub.hotspotStarts)
    }

    @Test
    fun activeStateBuildsQrFromPayload() = offlineTest {
        val vm = viewModel()
        vm.startOffline()
        hub.hotspotState.value = HotspotState.Starting
        runCurrent()
        assertEquals(1, hub.hotspotStarts)
        // Starting: the screen is up but has nothing to show yet.
        assertNull(vm.offline.value.screen!!.qrText)

        hub.hotspotState.value = HotspotState.Active("DIRECT-ab-Pixel", "pass1234word")
        runCurrent()
        // Active but the address isn't known yet: still no QR code.
        var screen = vm.offline.value.screen!!
        assertNull(screen.qrText)
        assertEquals("DIRECT-ab-Pixel", screen.ssid)

        hub.offlineEndpoint.value = OfflineEndpoint(address, record)
        runCurrent()
        screen = vm.offline.value.screen!!
        val payload = OfflineLinkPayload.parse(screen.qrText!!)!!
        assertEquals("DIRECT-ab-Pixel", payload.ssid)
        assertEquals("pass1234word", payload.passphrase)
        assertEquals(address, payload.address)
        assertEquals(49154, payload.helloPort)
        assertEquals(record, payload.record)
        assertEquals("192.168.43.1", screen.address)
        assertEquals("pass1234word", screen.passphrase)
        assertEquals("Stops in 10 min if idle", screen.minutesLeftLabel)
    }

    @Test
    fun countdownTracksIdleTimeAndPausesWhileBusy() = offlineTest {
        val vm = viewModel()
        vm.startOffline()
        hub.hotspotState.value = HotspotState.Active("n", "pass1234word")
        runCurrent()

        now += 4 * 60_000L
        advanceTimeBy(30_001)
        assertEquals("Stops in 6 min if idle", vm.offline.value.screen!!.minutesLeftLabel)

        hub.receiveState.value = ReceiveState.Receiving("a.txt", 1, 10)
        runCurrent()
        assertEquals("Transfer in progress", vm.offline.value.screen!!.minutesLeftLabel)

        hub.receiveState.value = ReceiveState.Listening
        runCurrent()
        assertEquals("Stops in 10 min if idle", vm.offline.value.screen!!.minutesLeftLabel)
    }

    @Test
    fun permissionDeniedShowsSettingsAction() = offlineTest {
        val vm = viewModel()

        vm.offlinePermissionDenied()
        runCurrent()

        val ui = vm.offline.value
        assertTrue(ui.cardVisible)
        assertEquals("LinkBridge needs Nearby devices to start the link", ui.message)
        assertTrue(ui.openSettings)

        vm.dismissOfflineMessage()
        runCurrent()
        assertNull(vm.offline.value.message)
        assertFalse(vm.offline.value.openSettings)
    }

    @Test
    fun eachFailureHasMessage() = offlineTest {
        val vm = viewModel()
        val seen = mutableSetOf<String>()
        for (reason in HotspotFailure.entries) {
            hub.hotspotState.value = HotspotState.Failed(reason)
            runCurrent()
            val ui = vm.offline.value
            val message = ui.message
            assertNotNull("$reason", message)
            assertTrue("$reason", message!!.isNotBlank())
            assertEquals(message, hotspotFailureMessage(reason))
            assertEquals("$reason", reason == HotspotFailure.PERMISSION_DENIED, ui.openSettings)
            seen += message
        }
        assertEquals(
            "Couldn't start offline link: turn off Mobile Hotspot and try again",
            hotspotFailureMessage(HotspotFailure.INCOMPATIBLE_MODE)
        )
        assertEquals(hotspotFailureMessage(HotspotFailure.INCOMPATIBLE_MODE), hotspotFailureMessage(HotspotFailure.TETHERING_ON))
        // Every reason but those two has its own text.
        assertEquals(HotspotFailure.entries.size - 1, seen.size)
    }

    @Test
    fun systemStopShowsNoticeButUserStopDoesNot() = offlineTest {
        val vm = viewModel()
        vm.startOffline()
        hub.hotspotState.value = HotspotState.Active("n", "pass1234word")
        runCurrent()

        hub.hotspotState.value = HotspotState.Stopped(bySystem = true)
        runCurrent()
        assertEquals("Offline link stopped", vm.offline.value.message)
        assertNull(vm.offline.value.screen)

        vm.startOffline()
        hub.hotspotState.value = HotspotState.Active("n", "pass1234word")
        runCurrent()
        vm.stopOffline()
        hub.hotspotState.value = HotspotState.Stopped(bySystem = false)
        runCurrent()
        assertEquals(1, hub.hotspotStops)
        assertNull(vm.offline.value.message)
        assertNull(vm.offline.value.screen)
    }

    // ---- setBusy ----

    private fun TestScope.activeVm(): MainViewModel {
        val vm = viewModel()
        hub.hotspotState.value = HotspotState.Active("n", "pass1234word")
        runCurrent()
        return vm
    }

    private val approval = PendingApproval(1, IncomingTransferRequest("Studio", MAC_ID, "a.txt", 10), expiresAt = Long.MAX_VALUE)

    @Test
    fun busyIsFalseWhenNothingIsHappening() = offlineTest {
        activeVm()
        assertEquals(false, hub.busyCalls.last())
    }

    @Test
    fun busyWhileReceiving() = offlineTest {
        activeVm()
        hub.receiveState.value = ReceiveState.Receiving("a.txt", 1, 10)
        runCurrent()
        assertEquals(true, hub.busyCalls.last())
        hub.receiveState.value = ReceiveState.Listening
        runCurrent()
        assertEquals(false, hub.busyCalls.last())
    }

    @Test
    fun busyWhileSending() = offlineTest {
        activeVm()
        hub.sendState.value = SendState.Progress("a.txt", 1, 10)
        runCurrent()
        assertEquals(true, hub.busyCalls.last())
        hub.sendState.value = SendState.Done("a.txt", 10, "Studio")
        runCurrent()
        assertEquals(false, hub.busyCalls.last())
    }

    @Test
    fun busyWhileApprovalPending() = offlineTest {
        activeVm()
        hub.pendingApprovals.value = listOf(approval)
        runCurrent()
        assertEquals(true, hub.busyCalls.last())
        hub.pendingApprovals.value = emptyList()
        runCurrent()
        assertEquals(false, hub.busyCalls.last())
    }

    @Test
    fun busyWhilePairing() = offlineTest {
        val stores = FakeStores()
        val pairing = FakePairing()
        hub.peers.value = listOf(unpairedMac)
        val vm = MainViewModel(
            hub, stores, pairing, FakePrefs(), CoroutineScope(SupervisorJob() + dispatcher), dispatcher,
            clock = { now }, zone = ZoneOffset.UTC, sdkInt = 34
        )
        runCurrent()
        vm.pair(unpairedMac.serviceName)
        runCurrent()
        assertEquals(true, hub.busyCalls.last())
        vm.cancelPairing()
        runCurrent()
        assertEquals(false, hub.busyCalls.last())
    }
}
