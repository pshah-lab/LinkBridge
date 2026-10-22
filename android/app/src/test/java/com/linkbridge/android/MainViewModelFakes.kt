package com.linkbridge.android

import com.linkbridge.android.LinkBridgeHub.SendState
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.presentation.ReceiveState
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

internal const val MAC_ID = "6f9619ff-8b86-d011-b42d-00cf4fc964ff"

internal val thisPhone = DeviceIdentity(deviceId = "11111111-2222-3333-4444-555555555555", deviceName = "Pixel")

/** An unpaired Mac as tag-based discovery reports it: no device ID yet. */
internal val unpairedMac = DiscoveredPeer(
    serviceName = "LinkBridge-Studio", deviceId = null, deviceName = "Studio", platform = "macos",
    host = "192.168.1.5", port = 52000, tlsPort = 52001
)

internal val macIdentity = DeviceIdentity(deviceId = MAC_ID, deviceName = "Studio", platform = "macos")

internal val pairedMac = unpairedMac.copy(deviceId = MAC_ID)

internal val macTrust = TrustedPeer(
    deviceId = MAC_ID, deviceName = "Studio", platform = "macos", host = "192.168.1.5", port = 52000,
    authToken = "token", pairedAt = 5L
)

internal class FakeHub : HubPort {
    override val peers = MutableStateFlow<List<DiscoveredPeer>>(emptyList())
    override val receiveState = MutableStateFlow<ReceiveState>(ReceiveState.Listening)
    override val sendState = MutableStateFlow<SendState?>(null)
    override val sendResults = MutableSharedFlow<SendState>(extraBufferCapacity = 64)
    override val pendingApprovals = MutableStateFlow<List<PendingApproval>>(emptyList())
    override val historyVersion = MutableStateFlow(0)

    val sends = mutableListOf<Triple<DiscoveredPeer, TrustedPeer, StagedFile>>()
    val resolved = mutableListOf<Pair<Int, Boolean>>()
    var rescans = 0
    var onRescan: () -> Unit = {}
    var sendAccepted = true
    var serviceRunning: Boolean? = null

    override fun resolveApproval(id: Int, accept: Boolean) { resolved += id to accept }
    override fun send(peer: DiscoveredPeer, trustedPeer: TrustedPeer, file: StagedFile): Boolean {
        sends += Triple(peer, trustedPeer, file)
        return sendAccepted
    }
    /** Like the service: every state goes to [sendState], finished ones to [sendResults] too. */
    fun publish(state: SendState) {
        sendState.value = state
        if (state !is SendState.Progress) sendResults.tryEmit(state)
    }

    override fun rescan() { rescans += 1; onRescan() }
    override fun startService() { serviceRunning = true }
    override fun stopService() { serviceRunning = false }
}

internal class FakeStores(
    var trustedPeers: List<TrustedPeer> = emptyList(),
    var entries: List<TransferHistoryEntry> = emptyList()
) : Stores {
    override fun identity(): DeviceIdentity = thisPhone
    override fun trusted(): List<TrustedPeer> = trustedPeers
    override fun saveTrusted(peer: TrustedPeer) { trustedPeers = trustedPeers.filterNot { it.deviceId == peer.deviceId } + peer }
    override fun removeTrusted(deviceId: String) { trustedPeers = trustedPeers.filterNot { it.deviceId == deviceId } }
    override fun history(): List<TransferHistoryEntry> = entries
}

internal class FakePairing : PairingClient {
    val calls = mutableListOf<String>()
    var device: Result<DeviceIdentity> = Result.success(macIdentity)
    var session: Result<PairingSession> = Result.success(PairingSession(code = "123456", authToken = "session-token"))
    var finalize: Result<Unit> = Result.success(Unit)

    override fun fetchDevice(peer: DiscoveredPeer): Result<DeviceIdentity> {
        calls += "fetch:${peer.serviceName}"
        return device
    }
    override fun beginPairing(peer: DiscoveredPeer, device: DeviceIdentity): Result<PairingSession> {
        calls += "begin:${device.deviceId}"
        return session
    }
    override fun finalizePairing(peer: DiscoveredPeer): Result<Unit> {
        calls += "finalize:${peer.serviceName}"
        return finalize
    }
}

internal class FakePrefs(override var selectedId: String? = null) : SelectionPrefs
