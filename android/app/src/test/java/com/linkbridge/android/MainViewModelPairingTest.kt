package com.linkbridge.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelPairingTest {
    private val dispatcher = StandardTestDispatcher()
    /** Its own scheduler: network and store calls run only when [settle] drains it. */
    private val io = StandardTestDispatcher()
    private val appScope = CoroutineScope(SupervisorJob() + io)
    private val hub = FakeHub()
    private val stores = FakeStores()
    private val pairing = FakePairing()
    private val prefs = FakePrefs()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        hub.peers.value = listOf(unpairedMac)
        // Saving trust and re-advertising lets discovery learn the Mac's device ID.
        hub.onRescan = { hub.peers.value = listOf(pairedMac) }
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.viewModel(): MainViewModel =
        MainViewModel(hub, stores, pairing, prefs, appScope, io, clock = { 1_000L }).also { settle() }

    /** Runs main and io work until both are idle. */
    private fun TestScope.settle() {
        repeat(5) {
            advanceUntilIdle()
            io.scheduler.advanceUntilIdle()
        }
        advanceUntilIdle()
    }

    @Test
    fun pairWalksConnectingCodeWaitingConfirmPaired() = runTest(dispatcher) {
        val vm = viewModel()

        vm.pair(unpairedMac.serviceName)
        runCurrent()
        assertEquals(PairingStep.Connecting("Studio"), vm.state.value.pairing)

        settle()
        assertEquals(PairingStep.Code("Studio", "123 456"), vm.state.value.pairing)

        vm.continuePairing()
        runCurrent()
        assertEquals(PairingStep.Waiting("Studio"), vm.state.value.pairing)

        settle()
        assertEquals(PairingStep.ConfirmOnMac("Studio"), vm.state.value.pairing)
        assertTrue("nothing saved before the user confirms", stores.trustedPeers.isEmpty())
        assertEquals(0, hub.rescans)

        vm.confirmPairedOnMac()
        settle()
        assertEquals(PairingStep.Paired("Studio"), vm.state.value.pairing)
        assertEquals(listOf("fetch:LinkBridge-Studio", "begin:$MAC_ID", "finalize:LinkBridge-Studio"), pairing.calls)
        assertEquals(
            listOf(macTrust.copy(authToken = "session-token", pairedAt = 1_000L, pairingKey = "MAC-KEY")),
            stores.trustedPeers
        )
        assertEquals(1, hub.rescans)
        assertEquals(MAC_ID, vm.state.value.selectedId)
        assertEquals(MAC_ID, prefs.selectedId)

        vm.dismissPairing()
        runCurrent()
        assertNull(vm.state.value.pairing)
    }

    @Test
    fun beginFailureShowsFailed() = runTest(dispatcher) {
        pairing.session = Result.failure(IllegalStateException("Pairing refused"))
        val vm = viewModel()

        vm.pair(unpairedMac.serviceName)
        settle()

        val step = vm.state.value.pairing as PairingStep.Failed
        assertEquals("Studio", step.name)
        assertEquals("Pairing refused", step.message)
        assertEquals(unpairedMac.serviceName, step.itemId)
        assertTrue(stores.trustedPeers.isEmpty())
    }

    @Test
    fun fetchFailureShowsFailedWithoutBegin() = runTest(dispatcher) {
        pairing.device = Result.failure(java.io.IOException("timeout"))
        val vm = viewModel()

        vm.pair(unpairedMac.serviceName)
        settle()

        assertEquals("timeout", (vm.state.value.pairing as PairingStep.Failed).message)
        assertEquals(listOf("fetch:LinkBridge-Studio"), pairing.calls)
    }

    @Test
    fun lateSuccessAfterCancelSavesNothing() = runTest(dispatcher) {
        val vm = viewModel()
        vm.pair(unpairedMac.serviceName)
        settle()
        vm.continuePairing()
        runCurrent()

        vm.cancelPairing()
        runCurrent()
        assertNull(vm.state.value.pairing)

        settle()
        assertTrue("Cancel while waiting withdraws the Mac's dialog", "cancel:${unpairedMac.serviceName}" in pairing.calls)
        assertNull(vm.state.value.pairing)
        assertTrue(stores.trustedPeers.isEmpty())
        assertEquals(0, hub.rescans)
        vm.confirmPairedOnMac()
        settle()
        assertTrue("a stray Yes after cancel saves nothing", stores.trustedPeers.isEmpty())
    }

    @Test
    fun lateSuccessAfterViewModelClearedSavesNothing() = runTest(dispatcher) {
        val store = ViewModelStore()
        val built = MainViewModel(hub, stores, pairing, prefs, appScope, io, clock = { 1_000L })
        val vm = ViewModelProvider(store, object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = built as T
        })[MainViewModel::class.java]
        settle()
        vm.pair(unpairedMac.serviceName)
        settle()
        vm.continuePairing()
        runCurrent()

        store.clear()
        settle()

        assertTrue(stores.trustedPeers.isEmpty())
        assertEquals(0, hub.rescans)
    }

    @Test
    fun answeringNoSavesNothing() = runTest(dispatcher) {
        val vm = viewModel()
        vm.pair(unpairedMac.serviceName)
        settle()
        vm.continuePairing()
        settle()
        assertEquals(PairingStep.ConfirmOnMac("Studio"), vm.state.value.pairing)

        vm.denyPairedOnMac()
        settle()

        assertTrue(vm.state.value.pairing is PairingStep.Failed)
        assertTrue(stores.trustedPeers.isEmpty())
        vm.confirmPairedOnMac()
        settle()
        assertTrue("Yes after No saves nothing", stores.trustedPeers.isEmpty())
    }

    @Test
    fun cancelAtConfirmSavesNothing() = runTest(dispatcher) {
        val vm = viewModel()
        vm.pair(unpairedMac.serviceName)
        settle()
        vm.continuePairing()
        settle()

        vm.dismissPairing()
        vm.confirmPairedOnMac()
        settle()

        assertNull(vm.state.value.pairing)
        assertTrue(stores.trustedPeers.isEmpty())
        // The Mac already answered, so there is no dialog left to withdraw.
        assertTrue(pairing.calls.none { it.startsWith("cancel:") })
    }

    @Test
    fun failedConfirmationShowsFailedAndSavesNothing() = runTest(dispatcher) {
        pairing.finalize = Result.failure(IllegalStateException("Pairing check failed"))
        val vm = viewModel()
        vm.pair(unpairedMac.serviceName)
        settle()
        vm.continuePairing()
        settle()

        assertEquals("Pairing check failed", (vm.state.value.pairing as PairingStep.Failed).message)
        assertTrue(stores.trustedPeers.isEmpty())
    }

    @Test
    fun rePairingFlagsSamePeerOrChangedKey() = runTest(dispatcher) {
        stores.trustedPeers = listOf(macTrust.copy(pairingKey = "MAC-KEY"))
        var vm = viewModel()
        vm.pair(unpairedMac.serviceName)
        settle()
        assertEquals(ExistingPairing.SAME_PEER, (vm.state.value.pairing as PairingStep.Code).existing)
        vm.cancelPairing()

        stores.trustedPeers = listOf(macTrust.copy(pairingKey = "OTHER-KEY"))
        vm = viewModel()
        vm.pair(unpairedMac.serviceName)
        settle()
        assertEquals(ExistingPairing.KEY_CHANGED, (vm.state.value.pairing as PairingStep.Code).existing)
    }

    @Test
    fun lateFailureAfterCancelIsDropped() = runTest(dispatcher) {
        pairing.finalize = Result.failure(java.io.IOException("declined"))
        val vm = viewModel()
        vm.pair(unpairedMac.serviceName)
        settle()
        vm.continuePairing()
        runCurrent()

        vm.cancelPairing()
        settle()

        assertNull(vm.state.value.pairing)
        assertTrue(stores.trustedPeers.isEmpty())
        assertEquals(0, hub.rescans)
    }

    @Test
    fun cancelDuringCodeNeverFinalizes() = runTest(dispatcher) {
        val vm = viewModel()
        vm.pair(unpairedMac.serviceName)
        settle()

        vm.cancelPairing()
        vm.continuePairing()
        settle()

        assertNull(vm.state.value.pairing)
        assertEquals(listOf("fetch:LinkBridge-Studio", "begin:$MAC_ID"), pairing.calls)
    }

    @Test
    fun cancelDuringConnectingDropsTheCode() = runTest(dispatcher) {
        val vm = viewModel()
        vm.pair(unpairedMac.serviceName)
        vm.cancelPairing()
        settle()

        assertNull(vm.state.value.pairing)
    }

    @Test
    fun pairIgnoresDevicesThatCannotPair() = runTest(dispatcher) {
        hub.peers.value = listOf(unpairedMac.copy(tlsPort = null))
        val vm = viewModel()

        vm.pair(unpairedMac.serviceName)
        settle()

        assertNull(vm.state.value.pairing)
        assertTrue(pairing.calls.isEmpty())
    }
}
