package com.linkbridge.android

import android.content.Context
import android.net.Uri
import com.linkbridge.android.LinkBridgeHub.SendState
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.presentation.ReceiveState
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The parts of [LinkBridgeHub] that [MainViewModel] uses, so tests can fake them. */
interface HubPort {
    val peers: StateFlow<List<DiscoveredPeer>>
    val receiveState: StateFlow<ReceiveState>
    val sendState: StateFlow<SendState?>
    /** Every finished send, one event each; see [LinkBridgeHub.sendResults]. */
    val sendResults: SharedFlow<SendState>
    val pendingApprovals: StateFlow<List<PendingApproval>>
    val historyVersion: StateFlow<Int>
    fun resolveApproval(id: Int, accept: Boolean)
    /** False if the service is not running. */
    fun send(peer: DiscoveredPeer, trustedPeer: TrustedPeer, file: StagedFile): Boolean
    fun rescan()
    fun startService()
    fun stopService()
}

/** Identity, trusted peers and history. Blocking; call off the main thread. */
interface Stores {
    fun identity(): DeviceIdentity
    fun trusted(): List<TrustedPeer>
    fun saveTrusted(peer: TrustedPeer)
    fun removeTrusted(deviceId: String)
    fun history(): List<TransferHistoryEntry>
}

/** The user's chosen send target. */
interface SelectionPrefs {
    var selectedId: String?
}

class LinkBridgeHubPort(private val context: Context) : HubPort {
    override val peers get() = LinkBridgeHub.peers
    override val receiveState get() = LinkBridgeHub.receiveState
    override val sendState get() = LinkBridgeHub.sendState
    override val sendResults get() = LinkBridgeHub.sendResults
    override val pendingApprovals get() = LinkBridgeHub.pendingApprovals
    override val historyVersion get() = LinkBridgeHub.historyVersion

    override fun resolveApproval(id: Int, accept: Boolean) = LinkBridgeHub.resolveApproval(id, accept)

    override fun send(peer: DiscoveredPeer, trustedPeer: TrustedPeer, file: StagedFile): Boolean =
        LinkBridgeHub.send(peer, trustedPeer, Uri.parse(file.uri), file.persistable)

    override fun rescan() = LinkBridgeHub.rescan()
    override fun startService() = LinkBridgeHub.startService(context)
    override fun stopService() = LinkBridgeHub.stopService(context)
}

class AppStores(context: Context) : Stores {
    private val identityStore = IdentityStore(context)
    private val trustedStore = TrustedPeerStore(context)
    private val historyStore = TransferHistoryStore(context)
    private val identity by lazy { identityStore.loadOrCreate() }

    override fun identity(): DeviceIdentity = identity
    override fun trusted(): List<TrustedPeer> = trustedStore.load()
    override fun saveTrusted(peer: TrustedPeer) = trustedStore.save(peer)
    override fun removeTrusted(deviceId: String) = trustedStore.remove(deviceId)
    override fun history(): List<TransferHistoryEntry> = historyStore.load()
}

/** `selected_device` in the `linkbridge_ui` preferences. */
class SharedPrefsSelection(context: Context) : SelectionPrefs {
    private val preferences = context.getSharedPreferences("linkbridge_ui", Context.MODE_PRIVATE)

    override var selectedId: String?
        get() = preferences.getString(KEY, null)
        set(value) {
            preferences.edit().apply { if (value == null) remove(KEY) else putString(KEY, value) }.apply()
        }

    private companion object {
        const val KEY = "selected_device"
    }
}

/** [SharePort] over the hub, for the [uris] [ShareActivity] accepted. Shared URIs are not persistable. */
class LinkBridgeSharePort(context: Context, private val uris: List<Uri>) : SharePort {
    private val context = context.applicationContext

    override val peers get() = LinkBridgeHub.peers
    override val sendState get() = LinkBridgeHub.sendState
    override val sendResults get() = LinkBridgeHub.sendResults

    override fun trusted(): List<TrustedPeer> = TrustedPeerStore(context).load()

    override fun fileNames(): List<String> = uris.map { uri ->
        runCatching { context.contentResolver.fileInfo(uri).name }.getOrDefault("file")
    }

    override fun send(peer: DiscoveredPeer, trustedPeer: TrustedPeer, index: Int): Boolean =
        LinkBridgeHub.send(peer, trustedPeer, uris[index], persistable = false)
}
