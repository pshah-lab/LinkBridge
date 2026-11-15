package com.linkbridge.android.offline

import java.net.Inet4Address
import java.net.NetworkInterface

/** What [findHotspotAddress] needs to know about one network interface; a plain class so it is testable. */
data class NetInterfaceInfo(
    val name: String,
    val isUp: Boolean,
    val isLoopback: Boolean,
    val ipv4s: List<Inet4Address>,
)

/**
 * Interface-name prefixes a soft AP may use. A guess until the device spike (Task 0) confirms it:
 * "wlan" also matches the station interface, which is why that address is excluded explicitly.
 */
val DEFAULT_HOTSPOT_INTERFACE_PREFIXES = listOf("swlan", "ap", "wlan")

private const val BARE_STATION_PREFIX = "wlan"

/**
 * The phone's address on its own local-only hotspot: an up, non-loopback, private IPv4 that is not
 * a station Wi-Fi address, on an interface whose name starts with one of [prefixes]. With several
 * candidates, the earliest prefix wins.
 *
 * [stationAddresses] empty means the station address is unknown (not joined, or the lookup failed).
 * Then the bare "wlan" prefix is ignored, because it would match the station LAN address and the
 * receiver would listen on the wrong network; only the specific prefixes can qualify.
 */
fun findHotspotAddress(
    interfaces: List<NetInterfaceInfo>,
    stationAddresses: Collection<Inet4Address>,
    prefixes: List<String> = DEFAULT_HOTSPOT_INTERFACE_PREFIXES,
): Inet4Address? {
    val usable = if (stationAddresses.isEmpty()) prefixes.filter { it != BARE_STATION_PREFIX } else prefixes
    for (prefix in usable) {
        for (candidate in interfaces) {
            if (!candidate.isUp || candidate.isLoopback || !candidate.name.startsWith(prefix)) continue
            candidate.ipv4s.firstOrNull { it.isSiteLocalAddress && it !in stationAddresses }?.let { return it }
        }
    }
    return null
}

/** Snapshot of this device's interfaces; empty if the system refuses to list them. */
fun currentNetInterfaces(): List<NetInterfaceInfo> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList().map { nic ->
        NetInterfaceInfo(
            name = nic.name,
            isUp = nic.isUp,
            isLoopback = nic.isLoopback,
            ipv4s = nic.inetAddresses.toList().filterIsInstance<Inet4Address>(),
        )
    }
}.getOrDefault(emptyList())
