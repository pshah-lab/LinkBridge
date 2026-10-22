package com.linkbridge.android

/** The three pairing calls, in protocol order. Blocking; call off the main thread. */
interface PairingClient {
    fun fetchDevice(peer: DiscoveredPeer): Result<DeviceIdentity>
    fun beginPairing(peer: DiscoveredPeer, device: DeviceIdentity): Result<PairingSession>
    /** Blocks until the Mac user confirms, up to `Timeouts.PAIR_FINALIZE_READ_TIMEOUT_MS`. */
    fun finalizePairing(peer: DiscoveredPeer): Result<Unit>
}

/** [ControlClient] with this phone's identity, loaded on first use. */
class ControlPairingClient(
    private val control: ControlClient,
    identity: () -> DeviceIdentity
) : PairingClient {
    private val identity by lazy(identity)

    override fun fetchDevice(peer: DiscoveredPeer): Result<DeviceIdentity> = control.fetchDevice(peer)

    override fun beginPairing(peer: DiscoveredPeer, device: DeviceIdentity): Result<PairingSession> =
        control.beginPairing(peer, identity, device.deviceId)

    override fun finalizePairing(peer: DiscoveredPeer): Result<Unit> = control.finalizePairing(peer, identity)
}
