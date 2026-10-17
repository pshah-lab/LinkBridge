package com.linkbridge.android

import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * In-process link between [LinkBridgeService] (which owns discovery, receiving and
 * sending) and whatever UI is visible. All listener callbacks run on the main thread.
 */
object LinkBridgeHub {
    interface Listener {
        fun onPeersChanged(peers: List<DiscoveredPeer>) {}
        fun onReceiveStatus(status: String) {}
        fun onActivityLogged(message: String, isOutgoing: Boolean) {}
        fun onSendStateChanged(state: SendState) {}
    }

    sealed interface SendState {
        data class Progress(val fileName: String, val sent: Long, val total: Long) : SendState
        data class Done(val fileName: String, val bytes: Long, val peerName: String) : SendState
        data class Failed(val fileName: String, val message: String) : SendState
    }

    /** Shows an approval prompt in the visible UI and reports the answer. */
    fun interface Approver {
        fun approve(request: IncomingTransferRequest, answer: (Boolean) -> Unit)
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()

    @Volatile var peers: List<DiscoveredPeer> = emptyList()
        private set
    @Volatile var receiveStatus: String = "Starting LinkBridge…"
        private set

    /** Set while an activity is in the foreground; incoming files are approved there. */
    @Volatile var foregroundApprover: Approver? = null

    @Volatile internal var service: LinkBridgeService? = null

    fun addListener(listener: Listener) {
        listeners += listener
        listener.onPeersChanged(peers)
        listener.onReceiveStatus(receiveStatus)
    }

    fun removeListener(listener: Listener) {
        listeners -= listener
    }

    fun rescan() {
        service?.rescan()
    }

    fun history(context: android.content.Context): List<TransferHistoryEntry> =
        TransferHistoryStore(context.applicationContext).load()

    /** Queues a send in the service. Returns false if the service is not running. */
    fun send(peer: DiscoveredPeer, trustedPeer: TrustedPeer, uri: Uri): Boolean {
        val running = service ?: return false
        running.send(peer, trustedPeer, uri)
        return true
    }

    internal fun publishPeers(newPeers: List<DiscoveredPeer>) {
        peers = newPeers
        main.post { listeners.forEach { it.onPeersChanged(newPeers) } }
    }

    internal fun publishReceiveStatus(status: String) {
        receiveStatus = status
        main.post { listeners.forEach { it.onReceiveStatus(status) } }
    }

    internal fun publishActivity(message: String, isOutgoing: Boolean) {
        main.post { listeners.forEach { it.onActivityLogged(message, isOutgoing) } }
    }

    internal fun publishSendState(state: SendState) {
        main.post { listeners.forEach { it.onSendStateChanged(state) } }
    }
}
