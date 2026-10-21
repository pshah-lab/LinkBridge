package com.linkbridge.android

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.presentation.ReceiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-process link between [LinkBridgeService] (which owns discovery, receiving and
 * sending) and whatever UI is visible. State is published as [StateFlow]s.
 */
object LinkBridgeHub {
    sealed interface SendState {
        data class Progress(val fileName: String, val sent: Long, val total: Long) : SendState
        data class Done(val fileName: String, val bytes: Long, val peerName: String) : SendState
        data class Failed(val fileName: String, val message: String) : SendState
    }

    private val _peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    val peers: StateFlow<List<DiscoveredPeer>> = _peers.asStateFlow()

    private val _receiveState = MutableStateFlow<ReceiveState>(ReceiveState.Starting)
    val receiveState: StateFlow<ReceiveState> = _receiveState.asStateFlow()

    private val _sendState = MutableStateFlow<SendState?>(null)
    val sendState: StateFlow<SendState?> = _sendState.asStateFlow()

    private val _historyVersion = MutableStateFlow(0)
    val historyVersion: StateFlow<Int> = _historyVersion.asStateFlow()

    /** The one approval resolver: in-app prompt, notification actions and timeout. */
    internal val approvals = ApprovalCenter(
        clock = System::currentTimeMillis,
        onWithdrawn = { id -> service?.cancelApprovalNotification(id) }
    )
    val pendingApprovals: StateFlow<List<PendingApproval>> = approvals.pending

    /** True while MainActivity is started; otherwise approvals go to a notification. */
    @Volatile var uiVisible: Boolean = false

    @Volatile internal var service: LinkBridgeService? = null

    fun resolveApproval(id: Int, accept: Boolean) {
        approvals.resolve(id, accept)
    }

    fun rescan() {
        service?.rescan()
    }

    fun history(context: Context): List<TransferHistoryEntry> =
        TransferHistoryStore(context.applicationContext).load()

    /**
     * Queues a send in the service. Returns false if the service is not running.
     * [persistable] says the caller took a persistable read grant on [uri], so a failed
     * send can keep the URI for Retry.
     */
    fun send(peer: DiscoveredPeer, trustedPeer: TrustedPeer, uri: Uri, persistable: Boolean): Boolean {
        val running = service ?: return false
        running.send(peer, trustedPeer, uri, persistable)
        return true
    }

    fun startService(context: Context) = LinkBridgeService.start(context)

    fun stopService(context: Context) {
        service?.stop()
    }

    internal fun publishPeers(newPeers: List<DiscoveredPeer>) {
        _peers.value = newPeers
        shimPost { it.onPeersChanged(newPeers) }
    }

    internal fun publishReceiveState(state: ReceiveState) {
        _receiveState.value = state
        val text = TransferRecords.legacyStatusText(state, LinkBridgeService.CONTROL_PORT)
        shimPost { it.onReceiveStatus(text) }
    }

    internal fun publishSendState(state: SendState) {
        _sendState.value = state
        shimPost { it.onSendStateChanged(state) }
    }

    /** Records [entry] and bumps [historyVersion]. */
    internal fun recordHistory(context: Context, entry: TransferHistoryEntry) {
        TransferHistoryStore(context.applicationContext).add(entry)
        _historyVersion.update { it + 1 }
        shimPost { it.onActivityLogged(entry.fileName, entry.isOutgoing) }
    }

    // ---- Shim for the old View UI, fed by the flows above. Task 12 deletes it. ----

    interface Listener {
        fun onPeersChanged(peers: List<DiscoveredPeer>) {}
        fun onReceiveStatus(status: String) {}
        fun onActivityLogged(message: String, isOutgoing: Boolean) {}
        fun onSendStateChanged(state: SendState) {}
    }

    /** Shows an approval prompt in the visible UI and reports the answer. */
    fun interface Approver {
        fun approve(request: IncomingTransferRequest, answer: (Boolean) -> Unit)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    /** Approval ids already shown by the shim's [Approver]; touched on the main thread only. */
    private val shownApprovals = HashSet<Int>()

    /** Set while an activity is resumed; pending approvals are prompted there. */
    @Volatile var foregroundApprover: Approver? = null
        set(value) {
            field = value
            main.post { promptPendingApprovals(pendingApprovals.value) }
        }

    init {
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            pendingApprovals.collect { pending -> main.post { promptPendingApprovals(pending) } }
        }
    }

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onPeersChanged(peers.value)
        listener.onReceiveStatus(TransferRecords.legacyStatusText(receiveState.value, LinkBridgeService.CONTROL_PORT))
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    /** Status text for the old UI's activity log, e.g. a declined background request. */
    internal fun publishActivity(message: String, isOutgoing: Boolean) {
        shimPost { it.onActivityLogged(message, isOutgoing) }
    }

    private fun shimPost(block: (Listener) -> Unit) {
        main.post { listeners.forEach(block) }
    }

    private fun promptPendingApprovals(pending: List<PendingApproval>) {
        shownApprovals.retainAll(pending.map { it.id }.toSet())
        val approver = foregroundApprover ?: return
        pending.filter { it.id !in shownApprovals }.forEach { approval ->
            shownApprovals += approval.id
            approver.approve(approval.request) { accept -> resolveApproval(approval.id, accept) }
        }
    }
}
