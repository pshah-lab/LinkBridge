package com.linkbridge.android.offline

import java.net.Inet4Address
import java.net.InetAddress

private const val ADDRESS_RETRY_DELAY_MS = 500L
private const val ADDRESS_RETRY_LIMIT = 10

/**
 * Ties the receiver and sender to the hotspot's lifecycle. While it is [HotspotState.Active] the
 * receiver accepts only connections that arrive on the hotspot address (refusing all until that
 * address is known, since the interface can come up just after the system reports the hotspot on).
 * When it stops, in-flight receives and sends are aborted and the receiver goes back to accepting
 * on any address. [onAddress] reports the address so other servers can bind to it. Call [onState] from one thread (the main thread).
 */
class HotspotBinding(
    private val setAcceptFilter: ((InetAddress) -> Boolean) -> Unit,
    private val abortReceives: () -> Unit,
    private val abortSends: () -> Unit,
    private val interfaces: () -> List<NetInterfaceInfo>,
    /** Every station Wi-Fi address; empty when none or the lookup failed. */
    private val stationAddresses: () -> Collection<Inet4Address>,
    private val schedule: (delayMs: Long, block: () -> Unit) -> Cancelable,
    private val prefixes: List<String> = DEFAULT_HOTSPOT_INTERFACE_PREFIXES,
    /** The hotspot address once known while Active; null in every other state. */
    private val onAddress: (Inet4Address?) -> Unit = {},
) {
    private var retry: Cancelable? = null

    fun onState(state: HotspotState) {
        retry?.cancel()
        retry = null
        onAddress(null)
        when (state) {
            is HotspotState.Active -> {
                setAcceptFilter { false }
                bindToHotspotAddress(attemptsLeft = ADDRESS_RETRY_LIMIT)
            }
            is HotspotState.Stopped -> {
                setAcceptFilter { true }
                abortReceives()
                abortSends()
            }
            else -> setAcceptFilter { true }
        }
    }

    private fun bindToHotspotAddress(attemptsLeft: Int) {
        val address = findHotspotAddress(interfaces(), stationAddresses(), prefixes)
        if (address != null) {
            setAcceptFilter { it == address }
            onAddress(address)
        } else if (attemptsLeft > 0) {
            retry = schedule(ADDRESS_RETRY_DELAY_MS) { bindToHotspotAddress(attemptsLeft - 1) }
        }
    }
}
