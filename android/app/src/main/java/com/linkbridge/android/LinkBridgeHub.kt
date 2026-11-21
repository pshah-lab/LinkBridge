package com.linkbridge.android

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.linkbridge.android.offline.Cancelable
import com.linkbridge.android.offline.DiscoveryRecord
import com.linkbridge.android.offline.OfflineHotspot
import com.linkbridge.android.offline.WifiManagerHotspotPort
import java.net.Inet4Address
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.presentation.ReceiveState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Where the hotspot's hello endpoint listens and who this phone is: what the QR code carries. */
data class OfflineEndpoint(val address: Inet4Address, val record: DiscoveryRecord)

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

    private val _sendResults = MutableSharedFlow<SendState>(extraBufferCapacity = RESULT_BUFFER)
    /**
     * Every finished send (Done or Failed), one event each. Unlike [sendState] nothing is
     * conflated, so a collector can count results. No replay: subscribe before sending.
     */
    val sendResults: SharedFlow<SendState> = _sendResults.asSharedFlow()

    private const val RESULT_BUFFER = 64

    private val _historyVersion = MutableStateFlow(0)
    val historyVersion: StateFlow<Int> = _historyVersion.asStateFlow()

    private val _offlineEndpoint = MutableStateFlow<OfflineEndpoint?>(null)
    /** Set while the offline hotspot is up and its address is known. */
    val offlineEndpoint: StateFlow<OfflineEndpoint?> = _offlineEndpoint.asStateFlow()

    /** The UI is pairing a device; the one busy input the service can't see. */
    val pairingInProgress = MutableStateFlow(false)

    private var offlineHotspot: OfflineHotspot? = null

    /** The process-wide offline hotspot, created on first use. Main thread only. */
    fun hotspot(context: Context): OfflineHotspot = offlineHotspot ?: run {
        val main = Handler(Looper.getMainLooper())
        OfflineHotspot(
            port = WifiManagerHotspotPort(context),
            sdkInt = Build.VERSION.SDK_INT,
            clock = System::currentTimeMillis,
            schedule = { delayMs, block ->
                val task = Runnable(block)
                main.postDelayed(task, delayMs)
                Cancelable { main.removeCallbacks(task) }
            },
        ).also {
            offlineHotspot = it
            service?.attachHotspot(it)
        }
    }

    /** The hotspot if something created it; the service attaches it to its lifecycle. */
    internal val hotspotOrNull: OfflineHotspot? get() = offlineHotspot

    /** The one approval resolver: in-app prompt, notification actions and timeout. */
    internal val approvals = ApprovalCenter(
        clock = System::currentTimeMillis,
        onWithdrawn = { id -> service?.cancelApprovalNotification(id) }
    )
    val pendingApprovals: StateFlow<List<PendingApproval>> = approvals.pending

    /**
     * True while MainActivity is started; otherwise approvals go to a notification.
     * Going false also posts one for each request the in-app sheet was still showing.
     */
    @Volatile var uiVisible: Boolean = false
        set(value) {
            field = value
            if (!value) service?.notifyPendingApprovals()
        }

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
    }

    internal fun publishOfflineEndpoint(endpoint: OfflineEndpoint?) {
        _offlineEndpoint.value = endpoint
    }

    internal fun publishReceiveState(state: ReceiveState) {
        _receiveState.value = state
    }

    internal fun publishSendState(state: SendState) {
        _sendState.value = state
        if (state !is SendState.Progress) _sendResults.tryEmit(state)
    }

    /** Records [entry] and bumps [historyVersion]. */
    internal fun recordHistory(context: Context, entry: TransferHistoryEntry) {
        TransferHistoryStore(context.applicationContext).add(entry)
        _historyVersion.update { it + 1 }
    }
}
