package com.linkbridge.android.offline

import com.linkbridge.android.DiscoveredPeer
import java.net.Inet4Address

/** A peer that answered the offline hello, as the discovery list shows it. */
fun DiscoveryRecord.toPeer(host: Inet4Address, deviceId: String?) = DiscoveredPeer(
    serviceName = "offline-$rid",
    deviceId = deviceId,
    deviceName = deviceName,
    platform = platform,
    host = host.hostAddress ?: "",
    port = port,
    tlsPort = tlsPort,
    rid = rid,
    tags = tags,
)

/**
 * Bonjour and hello peers in one list, one entry per device. Two entries are the same device when
 * their paired-device ID matches, else when their `rid` matches. Bonjour entries win.
 */
fun mergePeers(
    bonjour: List<DiscoveredPeer>,
    hello: List<DiscoveredPeer>,
    matchDeviceId: (rid: String, tags: String) -> String?,
): List<DiscoveredPeer> {
    val seen = mutableSetOf<String>()
    val merged = mutableListOf<DiscoveredPeer>()
    for (peer in bonjour + hello) {
        val id = peer.rid?.let { matchDeviceId(it, peer.tags) } ?: peer.deviceId
        val keys = listOfNotNull(id?.let { "id:$it" }, peer.rid?.let { "rid:$it" })
        if (keys.isEmpty()) {
            merged += peer
        } else if (keys.none { it in seen }) {
            seen += keys
            merged += peer
        }
    }
    return merged
}
