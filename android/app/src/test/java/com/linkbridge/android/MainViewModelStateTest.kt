package com.linkbridge.android

import com.linkbridge.android.LinkBridgeHub.SendState
import com.linkbridge.android.presentation.BannerAction
import com.linkbridge.android.presentation.ConnectionState
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.presentation.ReceiveState
import com.linkbridge.android.presentation.SendPhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelStateTest {
    private val dispatcher = StandardTestDispatcher()
    private val hub = FakeHub()
    private val stores = FakeStores()
    private val prefs = FakePrefs()
    private val file = StagedFile(uri = "content://docs/a.txt", name = "a.txt", size = 100, persistable = true)

    /** 2026-10-21 12:00 UTC. */
    private val now = 1_792_584_000_000L
    private val day = 86_400_000L

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): MainViewModel =
        MainViewModel(hub, stores, FakePairing(), prefs, CoroutineScope(SupervisorJob() + dispatcher), dispatcher, clock = { now }, zone = ZoneOffset.UTC)
            .also { advanceUntilIdle() }

    private fun TestScope.pairedViewModel(): MainViewModel {
        stores.trustedPeers = listOf(macTrust)
        hub.peers.value = listOf(pairedMac)
        return viewModel()
    }

    @Test
    fun sendRefusedWithoutTrustedTarget() = runTest(dispatcher) {
        hub.peers.value = listOf(unpairedMac)
        val vm = viewModel()

        vm.stage(file)
        vm.send()
        advanceUntilIdle()

        assertTrue(hub.sends.isEmpty())
        assertNull(vm.state.value.selectedId)
        assertEquals(SendPhase.Ready("a.txt"), vm.state.value.sendPhase)
    }

    @Test
    fun sendRefusedWhenTrustedDeviceIsAway() = runTest(dispatcher) {
        stores.trustedPeers = listOf(macTrust)
        val vm = viewModel()

        vm.stage(file)
        vm.send()
        advanceUntilIdle()

        assertTrue(hub.sends.isEmpty())
    }

    @Test
    fun sendGoesToTheSelectedTrustedPeer() = runTest(dispatcher) {
        val vm = pairedViewModel()
        assertEquals(MAC_ID, vm.state.value.selectedId)

        vm.stage(file)
        vm.send()
        advanceUntilIdle()

        assertEquals(listOf(Triple(pairedMac, macTrust, file)), hub.sends)
        assertEquals(SendPhase.Sending("a.txt", 0f), vm.state.value.sendPhase)
        assertEquals(ConnectionState.Sending("Studio", 0f), vm.state.value.connection)
    }

    @Test
    fun sendFailsWhenServiceIsNotRunning() = runTest(dispatcher) {
        hub.sendAccepted = false
        val vm = pairedViewModel()

        vm.stage(file)
        vm.send()
        advanceUntilIdle()

        assertTrue(vm.state.value.sendPhase is SendPhase.Failed)
    }

    @Test
    fun sendPhaseFollowsHubSendState() = runTest(dispatcher) {
        val vm = pairedViewModel()
        vm.stage(file)
        vm.send()
        advanceUntilIdle()

        hub.sendState.value = SendState.Progress("a.txt", sent = 50, total = 100)
        runCurrent()
        assertEquals(SendPhase.Sending("a.txt", 0.5f), vm.state.value.sendPhase)
        assertEquals(LiveRow.Sending("a.txt", 0.5f, "Studio"), vm.state.value.live.single())

        hub.sendState.value = SendState.Done("a.txt", bytes = 100, peerName = "Studio")
        runCurrent()
        assertEquals(SendPhase.Sent("a.txt"), vm.state.value.sendPhase)

        advanceTimeBy(1_999)
        runCurrent()
        assertEquals(SendPhase.Sent("a.txt"), vm.state.value.sendPhase)
        advanceTimeBy(2)
        runCurrent()
        assertEquals(SendPhase.Idle, vm.state.value.sendPhase)
        assertNull(vm.state.value.stagedFile)

        vm.stage(file)
        vm.send()
        hub.sendState.value = SendState.Failed("a.txt", "Connection refused")
        runCurrent()
        assertEquals(SendPhase.Failed("a.txt", "Connection refused"), vm.state.value.sendPhase)
        assertEquals(file, vm.state.value.stagedFile)
    }

    @Test
    fun staleFinishedSendStateIsIgnoredOnStart() = runTest(dispatcher) {
        hub.sendState.value = SendState.Failed("old.txt", "gone")
        val vm = pairedViewModel()

        assertEquals(SendPhase.Idle, vm.state.value.sendPhase)
    }

    @Test
    fun selectionPersistsAndPrefersTheSavedDevice() = runTest(dispatcher) {
        val other = TrustedPeer("7f9619ff-8b86-d011-b42d-00cf4fc964ff", "Air", "macos", "192.168.1.6", 52000, "t")
        stores.trustedPeers = listOf(macTrust, other)
        hub.peers.value = listOf(pairedMac, pairedMac.copy(serviceName = "LinkBridge-Air", deviceId = other.deviceId, deviceName = "Air"))
        prefs.selectedId = MAC_ID
        val vm = viewModel()
        assertEquals(MAC_ID, vm.state.value.selectedId)

        vm.select(other.deviceId)
        runCurrent()
        assertEquals(other.deviceId, vm.state.value.selectedId)
        assertEquals(other.deviceId, prefs.selectedId)
    }

    @Test
    fun unpairForgetsTrustAndRescans() = runTest(dispatcher) {
        val vm = pairedViewModel()

        vm.unpair(MAC_ID)
        advanceUntilIdle()

        assertTrue(stores.trustedPeers.isEmpty())
        assertTrue(vm.state.value.trusted.isEmpty())
        assertEquals(1, hub.rescans)
        assertNull(vm.state.value.selectedId)
    }

    private val failedSend = TransferHistoryEntry(
        fileName = "a.txt", bytes = 100, peerName = "Studio", isOutgoing = true, completedAt = now,
        succeeded = false, detail = "refused", contentUri = "content://docs/a.txt"
    )

    @Test
    fun retrySendsToThePeerItFailedOn() = runTest(dispatcher) {
        val air = TrustedPeer("7f9619ff-8b86-d011-b42d-00cf4fc964ff", "Air", "macos", "192.168.1.6", 52000, "t")
        val airPeer = pairedMac.copy(serviceName = "LinkBridge-Air", deviceId = air.deviceId, deviceName = "Air")
        stores.trustedPeers = listOf(macTrust, air)
        hub.peers.value = listOf(pairedMac, airPeer)
        prefs.selectedId = air.deviceId
        val vm = viewModel()
        assertEquals(air.deviceId, vm.state.value.selectedId)

        vm.retry(failedSend)
        advanceUntilIdle()

        assertEquals(listOf(Triple(pairedMac, macTrust, file)), hub.sends)
        assertEquals(MAC_ID, vm.state.value.selectedId)
    }

    @Test
    fun retryRefusedWhenThatPeerIsNotSendable() = runTest(dispatcher) {
        val air = TrustedPeer("7f9619ff-8b86-d011-b42d-00cf4fc964ff", "Air", "macos", "192.168.1.6", 52000, "t")
        stores.trustedPeers = listOf(macTrust, air)
        // Studio is paired but away; only Air is nearby and sendable.
        hub.peers.value = listOf(pairedMac.copy(serviceName = "LinkBridge-Air", deviceId = air.deviceId, deviceName = "Air"))
        val vm = viewModel()

        vm.retry(failedSend)
        advanceUntilIdle()

        assertTrue(hub.sends.isEmpty())
        assertNull(vm.state.value.stagedFile)
        assertEquals("Studio isn't available — pair it or bring it nearby, then retry.", vm.state.value.notice)

        vm.clearNotice()
        runCurrent()
        assertNull(vm.state.value.notice)
    }

    @Test
    fun historyGroupsByDayAndReloadsOnVersion() = runTest(dispatcher) {
        stores.entries = listOf(
            TransferHistoryEntry("today.txt", 1, "Studio", isOutgoing = true, completedAt = now - 1_000),
            TransferHistoryEntry("yesterday.txt", 1, "Studio", isOutgoing = false, completedAt = now - day)
        )
        val vm = viewModel()
        assertEquals(listOf("Today", "Yesterday"), vm.state.value.history.map { it.label })
        assertEquals("today.txt", vm.state.value.history.first().rows.single().entry.fileName)

        stores.entries = emptyList()
        hub.historyVersion.value = 1
        advanceUntilIdle()
        assertTrue(vm.state.value.history.isEmpty())
    }

    @Test
    fun liveRowsShowApprovalsAndReceiving() = runTest(dispatcher) {
        val approval = PendingApproval(7, IncomingTransferRequest("Studio", MAC_ID, "b.pdf", 10), expiresAt = now + 60_000)
        hub.pendingApprovals.value = listOf(approval)
        hub.receiveState.value = ReceiveState.Receiving("c.zip", received = 5, total = 10)
        val vm = viewModel()

        assertEquals(listOf(LiveRow.Waiting(approval), LiveRow.Receiving("c.zip", 5, 10)), vm.state.value.live)
        assertEquals(listOf(approval), vm.state.value.approvals)

        vm.resolveApproval(7, accept = true)
        assertEquals(listOf(7 to true), hub.resolved)
    }

    @Test
    fun bannerAndReceivingFollowReceiveStateAndNotifications() = runTest(dispatcher) {
        val vm = viewModel()
        assertNull(vm.state.value.banner)
        assertTrue(vm.state.value.receivingOn)
        assertEquals(thisPhone, vm.state.value.thisDevice)

        vm.notificationsChanged(false)
        runCurrent()
        assertEquals(BannerAction.ENABLE_NOTIFICATIONS, vm.state.value.banner?.action)

        hub.receiveState.value = ReceiveState.Stopped
        runCurrent()
        assertEquals(BannerAction.START, vm.state.value.banner?.action)
        assertFalse(vm.state.value.receivingOn)

        vm.setReceiving(true)
        assertEquals(true, hub.serviceRunning)
        vm.setReceiving(false)
        assertEquals(false, hub.serviceRunning)
    }

    @Test
    fun receiveFailureIsTheConnectionError() = runTest(dispatcher) {
        hub.receiveState.value = ReceiveState.Failed("Port in use")
        val vm = pairedViewModel()

        assertEquals(ConnectionState.Error("Port in use"), vm.state.value.connection)
    }

    @Test
    fun restartReceivingStartsOnlyAfterTheServiceStopped() = runTest(dispatcher) {
        hub.receiveState.value = ReceiveState.Failed("Port in use")
        val vm = viewModel()

        vm.restartReceiving()
        runCurrent()
        assertEquals(false, hub.serviceRunning)

        hub.receiveState.value = ReceiveState.Stopped
        runCurrent()
        assertEquals(true, hub.serviceRunning)
    }

    @Test
    fun restartReceivingStartsAnywayAfterTheWait() = runTest(dispatcher) {
        hub.receiveState.value = ReceiveState.Failed("Port in use")
        val vm = viewModel()

        vm.restartReceiving()
        advanceTimeBy(4_999)
        assertEquals(false, hub.serviceRunning)
        advanceTimeBy(2)
        assertEquals(true, hub.serviceRunning)
    }
}
