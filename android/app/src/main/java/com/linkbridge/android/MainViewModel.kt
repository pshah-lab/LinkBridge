package com.linkbridge.android

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.linkbridge.android.LinkBridgeHub.SendState
import com.linkbridge.android.presentation.ConnectionState
import com.linkbridge.android.presentation.DeviceListItem
import com.linkbridge.android.presentation.DeviceSelection
import com.linkbridge.android.presentation.HistoryRowContent
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.presentation.ReceiveState
import com.linkbridge.android.presentation.SendPhase
import com.linkbridge.android.presentation.bannerFor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Turns the hub, the stores and the pairing flow into one [UiState]. All mutable state is
 * touched on the main thread; store and network calls run on [io].
 */
class MainViewModel(
    private val hub: HubPort,
    private val stores: Stores,
    private val pairingClient: PairingClient,
    private val prefs: SelectionPrefs,
    /** Outlives the view model, so a finalize that succeeds after the screen closes is still saved. */
    private val appScope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val zone: ZoneId = ZoneId.systemDefault()
) : ViewModel() {

    private val trusted = MutableStateFlow<List<TrustedPeer>>(emptyList())
    private val thisDevice = MutableStateFlow<DeviceIdentity?>(null)
    private val history = MutableStateFlow<List<TransferHistoryEntry>>(emptyList())
    private val selection = MutableStateFlow<String?>(null)
    private val sendPhase = MutableStateFlow<SendPhase>(SendPhase.Idle)
    private val staged = MutableStateFlow<StagedFile?>(null)
    private val pairing = MutableStateFlow<PairingStep?>(null)
    private val notificationsAllowed = MutableStateFlow(true)
    private val notice = MutableStateFlow<String?>(null)

    private val devices: StateFlow<List<DeviceListItem>> =
        combine(hub.peers, trusted) { nearby, paired -> DeviceListItem.merge(nearby, paired) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val state: StateFlow<UiState> = combine(
        combine(devices, selection, sendPhase, staged, trusted, ::SendInputs),
        combine(hub.receiveState, hub.pendingApprovals, history, notificationsAllowed, pairing, ::OtherInputs),
        thisDevice,
        notice
    ) { send, other, identity, message -> buildState(send, other, identity, message) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, UiState())

    /** Bumped by every cancel or new pair, so a stale network result knows it was abandoned. */
    private var pairingGeneration = 0
    private var pendingPair: PendingPair? = null
    private var sentResetJob: Job? = null

    private class PendingPair(val peer: DiscoveredPeer, val device: DeviceIdentity, val session: PairingSession)

    init {
        viewModelScope.launch {
            val (identity, peers) = withContext(io) { stores.identity() to stores.trusted() }
            thisDevice.value = identity
            trusted.value = peers
        }
        viewModelScope.launch {
            hub.historyVersion.collect { history.value = withContext(io) { stores.history() } }
        }
        viewModelScope.launch {
            combine(devices, sendPhase) { items, phase -> items to (phase is SendPhase.Sending) }
                .collect { (items, sending) -> resolveSelection(items, sending) }
        }
        viewModelScope.launch {
            var first = true
            hub.sendState.collect { sendState ->
                // A finished send from before this view model is history, not the current state.
                val stale = first && sendState !is SendState.Progress
                first = false
                if (!stale && sendState != null) onSendState(sendState)
            }
        }
    }

    // ---- Devices ----

    fun select(id: String) {
        prefs.selectedId = id
        resolveSelection(devices.value, sendPhase.value is SendPhase.Sending)
    }

    fun rescan() = hub.rescan()

    fun unpair(deviceId: String) {
        viewModelScope.launch {
            trusted.value = withContext(io) {
                stores.removeTrusted(deviceId)
                stores.trusted()
            }
            if (prefs.selectedId == deviceId) prefs.selectedId = null
            // Re-advertise so the removed peer's discovery tag is withdrawn.
            hub.rescan()
        }
    }

    private fun resolveSelection(items: List<DeviceListItem>, isSending: Boolean) {
        selection.value = DeviceSelection.resolve(selection.value, prefs.selectedId, items, isSending)
    }

    // ---- Pairing: fetchDevice → beginPairing → show code → Continue → finalizePairing ----

    fun pair(id: String) {
        val item = devices.value.firstOrNull { it.id == id } ?: return
        val peer = item.peer ?: return
        if (!item.canPair || pairing.value.isRunning()) return
        val generation = ++pairingGeneration
        pendingPair = null
        pairing.value = PairingStep.Connecting(item.name)
        viewModelScope.launch {
            val result = withContext(io) {
                pairingClient.fetchDevice(peer).mapCatching { device ->
                    device to pairingClient.beginPairing(peer, device).getOrThrow()
                }
            }
            if (generation != pairingGeneration) return@launch
            result
                .onSuccess { (device, session) ->
                    pendingPair = PendingPair(peer, device, session)
                    pairing.value = PairingStep.Code(device.deviceName, PairingCode.display(session.code))
                }
                .onFailure { pairing.value = PairingStep.Failed(item.name, it.reason(), id) }
        }
    }

    fun continuePairing() {
        val pending = pendingPair ?: return
        if (pairing.value !is PairingStep.Code) return
        pendingPair = null
        val generation = pairingGeneration
        val device = pending.device
        pairing.value = PairingStep.Waiting(device.deviceName)
        // The finalize and the save run in appScope: if the Mac pairs, the phone must trust it
        // even when the user cancelled or left the screen meanwhile.
        appScope.launch(io) {
            val saved = pairingClient.finalizePairing(pending.peer).mapCatching {
                stores.saveTrusted(
                    TrustedPeer(
                        deviceId = device.deviceId,
                        deviceName = device.deviceName,
                        platform = device.platform,
                        host = pending.peer.host,
                        port = pending.peer.port,
                        authToken = pending.session.authToken,
                        pairedAt = clock()
                    )
                )
                trusted.value = stores.trusted()
                // Re-advertise so the new peer's discovery tag is published.
                hub.rescan()
            }
            // UI updates only; dropped if the view model is gone.
            viewModelScope.launch {
                if (generation != pairingGeneration) return@launch
                saved
                    .onSuccess {
                        select(device.deviceId)
                        pairing.value = PairingStep.Paired(device.deviceName)
                    }
                    .onFailure { pairing.value = PairingStep.Failed(device.deviceName, it.reason(), pending.peer.idOf()) }
            }
        }
    }

    /** Closes the sheet. A finalize already running keeps going and is handled silently. */
    fun cancelPairing() {
        if (!pairing.value.isRunning()) return
        pairingGeneration += 1
        pendingPair = null
        pairing.value = null
    }

    /** Closes a finished (Paired or Failed) sheet; cancels a running one. */
    fun dismissPairing() {
        if (pairing.value.isRunning()) cancelPairing() else pairing.value = null
    }

    private fun PairingStep?.isRunning() =
        this is PairingStep.Connecting || this is PairingStep.Code || this is PairingStep.Waiting

    private fun DiscoveredPeer.idOf() = deviceId ?: serviceName

    private fun Throwable.reason() = localizedMessage ?: "Unknown error"

    // ---- Send ----

    fun stage(file: StagedFile) {
        if (sendPhase.value is SendPhase.Sending) return
        sentResetJob?.cancel()
        staged.value = file
        sendPhase.value = SendPhase.Ready(file.name)
    }

    /** Sends the staged file to the selected device, which must be sendable and trusted. */
    fun send() {
        val file = staged.value ?: return
        if (sendPhase.value is SendPhase.Sending) return
        val item = devices.value.firstOrNull { it.id == selection.value } ?: return
        if (!item.isSendable) return
        val peer = item.peer ?: return
        val trust = trusted.value.firstOrNull { it.deviceId == item.deviceId } ?: return
        sentResetJob?.cancel()
        sendPhase.value = if (hub.send(peer, trust, file)) {
            SendPhase.Sending(file.name, 0f)
        } else {
            SendPhase.Failed(file.name, "LinkBridge isn't running.")
        }
    }

    /**
     * Re-sends a failed outgoing transfer to the device it failed on: the sendable device named
     * [TransferHistoryEntry.peerName]. If none is sendable, nothing is sent and [UiState.notice] says why.
     */
    fun retry(entry: TransferHistoryEntry) {
        if (!entry.isOutgoing || entry.succeeded) return
        val uri = entry.contentUri ?: return
        if (sendPhase.value is SendPhase.Sending) return
        val target = devices.value.firstOrNull { it.isSendable && it.name == entry.peerName }
        if (target == null) {
            notice.value = "${entry.peerName} isn't available — pair it or bring it nearby, then retry."
            return
        }
        select(target.id)
        stage(StagedFile(uri, entry.fileName, entry.bytes, persistable = true))
        send()
    }

    /** The UI showed [UiState.notice]. */
    fun clearNotice() {
        notice.value = null
    }

    private fun onSendState(state: SendState) {
        when (state) {
            is SendState.Progress -> {
                val progress = if (state.total > 0) state.sent.toFloat() / state.total else 0f
                sendPhase.value = SendPhase.Sending(state.fileName, progress.coerceIn(0f, 1f))
            }
            is SendState.Done -> {
                sendPhase.value = SendPhase.Sent(state.fileName)
                staged.value = null
                sentResetJob?.cancel()
                sentResetJob = viewModelScope.launch {
                    delay(SENT_RESET_MS)
                    if (sendPhase.value is SendPhase.Sent) sendPhase.value = SendPhase.Idle
                }
            }
            is SendState.Failed -> sendPhase.value = SendPhase.Failed(state.fileName, state.message)
        }
    }

    // ---- Receiving, approvals, settings ----

    fun resolveApproval(id: Int, accept: Boolean) = hub.resolveApproval(id, accept)

    fun setReceiving(on: Boolean) = if (on) hub.startService() else hub.stopService()

    /**
     * Retry after a receive failure: a running service keeps its failed server, so stop it,
     * wait until it reports Stopped (or [RESTART_WAIT_MS] passes), then start a fresh one.
     */
    fun restartReceiving() {
        if (!restarting.compareAndSet(false, true)) return
        hub.stopService()
        // appScope: the restart must finish even if the screen closes during the wait.
        appScope.launch {
            try {
                withTimeoutOrNull(RESTART_WAIT_MS) { hub.receiveState.first { it is ReceiveState.Stopped } }
                hub.startService()
            } finally {
                restarting.set(false)
            }
        }
    }

    private val restarting = AtomicBoolean(false)

    fun notificationsChanged(allowed: Boolean) {
        notificationsAllowed.value = allowed
    }

    // ---- State ----

    private data class SendInputs(
        val devices: List<DeviceListItem>,
        val selectedId: String?,
        val sendPhase: SendPhase,
        val staged: StagedFile?,
        val trusted: List<TrustedPeer>
    )

    private data class OtherInputs(
        val receive: ReceiveState,
        val approvals: List<PendingApproval>,
        val history: List<TransferHistoryEntry>,
        val notificationsAllowed: Boolean,
        val pairing: PairingStep?
    )

    private fun buildState(send: SendInputs, other: OtherInputs, identity: DeviceIdentity?, notice: String?): UiState {
        val selected = send.devices.firstOrNull { it.id == send.selectedId }
        val live = buildList {
            (send.sendPhase as? SendPhase.Sending)?.let { add(LiveRow.Sending(it.fileName, it.progress, selected?.name.orEmpty())) }
            other.approvals.forEach { add(LiveRow.Waiting(it)) }
            (other.receive as? ReceiveState.Receiving)?.let { add(LiveRow.Receiving(it.fileName, it.received, it.total)) }
        }
        val now = clock()
        val sections = other.history
            .sortedByDescending { it.completedAt }
            .map { HistoryRowContent(it, now, zone) }
            .groupBy { it.day }
            .map { (label, rows) -> HistorySection(label, rows) }
        return UiState(
            devices = send.devices,
            selectedId = send.selectedId,
            connection = ConnectionState.derive(
                nearbyCount = send.devices.count { it.isNearby },
                selected = selected,
                sendPhase = send.sendPhase,
                errorMessage = (other.receive as? ReceiveState.Failed)?.message
            ),
            sendPhase = send.sendPhase,
            stagedFile = send.staged,
            live = live,
            history = sections,
            approvals = other.approvals,
            banner = bannerFor(other.receive, other.notificationsAllowed),
            pairing = other.pairing,
            thisDevice = identity,
            trusted = send.trusted,
            receivingOn = other.receive !is ReceiveState.Stopped,
            notificationsOn = other.notificationsAllowed,
            notice = notice
        )
    }

    /** Wires the production hub, stores, control client and preferences. */
    class Factory(private val application: Application) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            val stores = AppStores(application)
            return MainViewModel(
                hub = LinkBridgeHubPort(application),
                stores = stores,
                pairingClient = ControlPairingClient(ControlClient()) { stores.identity() },
                prefs = SharedPrefsSelection(application),
                appScope = appScope,
                io = Dispatchers.IO,
                clock = System::currentTimeMillis
            ) as T
        }
    }

    private companion object {
        const val SENT_RESET_MS = 2_000L
        const val RESTART_WAIT_MS = 5_000L

        /** Process-wide; never cancelled. */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
