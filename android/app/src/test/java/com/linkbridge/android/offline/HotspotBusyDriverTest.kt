package com.linkbridge.android.offline

import com.linkbridge.android.IncomingTransferRequest
import com.linkbridge.android.LinkBridgeHub.SendState
import com.linkbridge.android.MAC_ID
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.presentation.ReceiveState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/** The service-side busy signal: no view model exists in any of these. */
@OptIn(ExperimentalCoroutinesApi::class)
class HotspotBusyDriverTest {
    private val dispatcher = StandardTestDispatcher()
    private val receive = MutableStateFlow<ReceiveState>(ReceiveState.Listening)
    private val send = MutableStateFlow<SendState?>(null)
    private val approvals = MutableStateFlow<List<PendingApproval>>(emptyList())
    private val pairing = MutableStateFlow(false)
    private val hotspot = MutableStateFlow<HotspotState>(HotspotState.Active("n", "pass1234word"))
    private val calls = mutableListOf<Boolean>()

    private fun TestScope.driver() = HotspotBusyDriver(
        this, receive, send, approvals, pairing, hotspot, setBusy = { calls += it }
    ).also { runCurrent() }

    private val approval = PendingApproval(1, IncomingTransferRequest("Studio", MAC_ID, "a.txt", 10), expiresAt = Long.MAX_VALUE)

    @Test
    fun idleIsNotBusy() = runTest(dispatcher) {
        val d = driver()
        assertEquals(false, calls.last())
        d.stop()
    }

    @Test
    fun receiveInFlightKeepsItBusyWithNoViewModel() = runTest(dispatcher) {
        val d = driver()
        receive.value = ReceiveState.Receiving("a.txt", 1, 10)
        runCurrent()
        assertEquals(true, calls.last())
        receive.value = ReceiveState.Listening
        runCurrent()
        assertEquals(false, calls.last())
        d.stop()
    }

    @Test
    fun shareSheetStyleSendIsBusy() = runTest(dispatcher) {
        val d = driver()
        send.value = SendState.Progress("a.txt", 1, 10)
        runCurrent()
        assertEquals(true, calls.last())
        send.value = SendState.Done("a.txt", 10, "Studio")
        runCurrent()
        assertEquals(false, calls.last())
        d.stop()
    }

    @Test
    fun pendingApprovalIsBusy() = runTest(dispatcher) {
        val d = driver()
        approvals.value = listOf(approval)
        runCurrent()
        assertEquals(true, calls.last())
        approvals.value = emptyList()
        runCurrent()
        assertEquals(false, calls.last())
        d.stop()
    }

    @Test
    fun pairingFromTheUiIsOredIn() = runTest(dispatcher) {
        val d = driver()
        pairing.value = true
        runCurrent()
        assertEquals(true, calls.last())
        // The UI going away clears its own input; a transfer still holds the signal.
        receive.value = ReceiveState.Receiving("a.txt", 1, 10)
        pairing.value = false
        runCurrent()
        assertEquals(true, calls.last())
        d.stop()
    }

    @Test
    fun newHotspotSessionIsToldTheCurrentFlagAgain() = runTest(dispatcher) {
        val d = driver()
        receive.value = ReceiveState.Receiving("a.txt", 1, 10)
        runCurrent()
        calls.clear()
        hotspot.value = HotspotState.Idle
        hotspot.value = HotspotState.Active("n2", "pass1234word")
        runCurrent()
        assertEquals(true, calls.last())
        d.stop()
    }

    @Test
    fun stoppedDriverSendsNothingMore() = runTest(dispatcher) {
        val d = driver()
        d.stop()
        calls.clear()
        receive.value = ReceiveState.Receiving("a.txt", 1, 10)
        runCurrent()
        assertEquals(emptyList<Boolean>(), calls)
    }
}
