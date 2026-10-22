package com.linkbridge.android

import com.linkbridge.android.LinkBridgeHub.SendState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeSharePort(
    var trustedPeers: List<TrustedPeer> = listOf(macTrust),
    val names: List<String> = listOf("a.jpg", "b.jpg", "c.jpg"),
) : SharePort {
    override val peers = MutableStateFlow(listOf(pairedMac))
    override val sendState = MutableStateFlow<SendState?>(null)
    override val sendResults = MutableSharedFlow<SendState>(extraBufferCapacity = 64)
    val sent = mutableListOf<Int>()
    var accept = true

    override fun trusted() = trustedPeers
    override fun fileNames() = names
    override fun send(peer: DiscoveredPeer, trustedPeer: TrustedPeer, index: Int): Boolean {
        sent += index
        return accept
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShareViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val port = FakeSharePort()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(count: Int = 3) =
        ShareViewModel(port, count, dispatcher).also { advanceUntilIdle() }

    @Test
    fun copyMatchesSpec() {
        assertEquals("Send 3 files", ShareText.header(3))
        assertEquals("Send 1 file", ShareText.header(1))
        assertEquals("Sending 2 of 3 — photo.jpg", ShareText.sending(2, 3, "photo.jpg"))
        assertEquals("Sent 3 files to Studio", ShareText.sent(3, "Studio"))
        assertEquals("Sent 1 file to Studio", ShareText.sent(1, "Studio"))
    }

    @Test
    fun tallyCountsFinishedAndFailed() {
        var tally = ShareTally(3)
        assertEquals(1, tally.index)
        tally = tally.record(SendState.Done("a", 1, "Studio"))
        tally = tally.record(SendState.Progress("b", 1, 2))
        assertEquals(2, tally.index)
        tally = tally.record(SendState.Failed("b", "Connection reset"))
        assertFalse(tally.isComplete)
        tally = tally.record(SendState.Done("c", 1, "Studio"))
        assertTrue(tally.isComplete)
        assertEquals(3, tally.index)
        assertEquals(ShareStep.Failed("Sent 2 of 3 files to Studio", "Connection reset"), tally.outcome("Studio"))
    }

    @Test
    fun allFailedSaysCouldNotSend() {
        val tally = ShareTally(1).record(SendState.Failed("a", "Timed out"))
        assertEquals(ShareStep.Failed("Couldn't send to Studio", "Timed out"), tally.outcome("Studio"))
    }

    @Test
    fun noFilesIsUnsupported() = runTest(dispatcher) {
        assertEquals(ShareStep.Unsupported, viewModel(count = 0).state.value.step)
    }

    @Test
    fun listsOnlySendableDevices() = runTest(dispatcher) {
        port.peers.value = listOf(pairedMac, unpairedMac.copy(serviceName = "LinkBridge-Air", deviceName = "Air"))
        val vm = viewModel()
        assertEquals(listOf(MAC_ID), vm.state.value.targets.map { it.id })
        assertTrue(vm.state.value.hasPaired)
    }

    @Test
    fun noPairedDeviceHasNoTargets() = runTest(dispatcher) {
        port.trustedPeers = emptyList()
        val state = viewModel().state.value
        assertTrue(state.targets.isEmpty())
        assertFalse(state.hasPaired)
    }

    @Test
    fun sendsEveryFileAndCountsResults() = runTest(dispatcher) {
        val vm = viewModel()
        vm.send(MAC_ID)
        advanceUntilIdle()
        assertEquals(listOf(0, 1, 2), port.sent)
        assertEquals(ShareStep.Sending("Sending 1 of 3 — a.jpg", null), vm.state.value.step)
        assertTrue(vm.state.value.isSending)

        port.sendState.value = SendState.Progress("a.jpg", 50, 100)
        advanceUntilIdle()
        assertEquals(ShareStep.Sending("Sending 1 of 3 — a.jpg", 0.5f), vm.state.value.step)

        port.sendResults.emit(SendState.Done("a.jpg", 100, "Studio"))
        advanceUntilIdle()
        assertEquals("Sending 2 of 3 — b.jpg", (vm.state.value.step as ShareStep.Sending).text)

        // Identical results are still counted: results are events, not conflated state.
        port.sendResults.emit(SendState.Done("b.jpg", 100, "Studio"))
        port.sendResults.emit(SendState.Done("b.jpg", 100, "Studio"))
        advanceUntilIdle()
        assertEquals(ShareStep.Done("Sent 3 files to Studio"), vm.state.value.step)
        assertFalse(vm.state.value.isSending)
    }

    @Test
    fun failureShowsMessage() = runTest(dispatcher) {
        val vm = viewModel(count = 1)
        vm.send(MAC_ID)
        port.sendResults.emit(SendState.Failed("a.jpg", "Connection reset"))
        advanceUntilIdle()
        assertEquals(ShareStep.Failed("Couldn't send to Studio", "Connection reset"), vm.state.value.step)
    }

    @Test
    fun serviceNotRunningFailsAtOnce() = runTest(dispatcher) {
        port.accept = false
        val vm = viewModel()
        vm.send(MAC_ID)
        advanceUntilIdle()
        assertEquals(ShareStep.Failed("Couldn't send to Studio", ShareText.NOT_RUNNING), vm.state.value.step)
    }

    @Test
    fun resultsBeforeSendingAreIgnored() = runTest(dispatcher) {
        val vm = viewModel(count = 1)
        port.sendResults.emit(SendState.Done("other.txt", 1, "Studio"))
        advanceUntilIdle()
        assertEquals(ShareStep.Choose, vm.state.value.step)
        vm.send(MAC_ID)
        vm.send(MAC_ID)
        assertEquals(listOf(0), port.sent)
    }
}
