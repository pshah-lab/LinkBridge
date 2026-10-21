package com.linkbridge.android.presentation

import com.linkbridge.android.DiscoveredPeer
import com.linkbridge.android.TrustedPeer

enum class DevicePlatform {
    MAC, ANDROID, IPHONE, UNKNOWN;

    companion object {
        fun from(raw: String?): DevicePlatform = when (raw) {
            "macos" -> MAC
            "android" -> ANDROID
            "ios" -> IPHONE
            else -> UNKNOWN
        }
    }
}

/**
 * One row in the Devices list: a nearby peer, a paired device, or both.
 *
 * Unlike the Mac, a missing [deviceId] does not mean an old app: with tag-based discovery an
 * unpaired Mac advertises none. The old-app signal is a missing `tlsPort`. Row identity is
 * `deviceId ?: serviceName`.
 */
data class DeviceListItem(
    val id: String,
    val deviceId: String?,
    val name: String,
    val platform: DevicePlatform,
    val isPaired: Boolean,
    val isNearby: Boolean,
    val isSendable: Boolean,
    val canPair: Boolean,
    val subtitle: String,
    /** The discovered peer behind a nearby row; null for a paired device that is away. */
    val peer: DiscoveredPeer?
) {
    companion object {
        private val byName = compareBy(String.CASE_INSENSITIVE_ORDER, DeviceListItem::name)

        /**
         * Nearby paired devices, then nearby unpaired, then paired devices that are away; A→Z in each.
         * Duplicate identities (`deviceId ?: serviceName` nearby, `deviceId` trusted) keep the first,
         * so every row [id] is unique.
         */
        fun merge(nearby: List<DiscoveredPeer>, trusted: List<TrustedPeer>): List<DeviceListItem> {
            @Suppress("NAME_SHADOWING") val nearby = nearby.distinctBy { it.deviceId ?: it.serviceName }
            @Suppress("NAME_SHADOWING") val trusted = trusted.distinctBy { it.deviceId }
            val trustedIds = trusted.mapTo(HashSet()) { it.deviceId }
            val nearbyIds = nearby.mapNotNullTo(HashSet()) { it.deviceId }

            val nearbyItems = nearby.map { peer ->
                val platform = DevicePlatform.from(peer.platform)
                val paired = peer.deviceId != null && peer.deviceId in trustedIds
                val current = peer.tlsPort != null
                val canPair = !paired && current && platform == DevicePlatform.MAC
                val subtitle = when {
                    !current -> "Needs a LinkBridge update"
                    paired -> "Paired · Nearby"
                    canPair -> "Not paired"
                    else -> "Pair from the Mac app"
                }
                DeviceListItem(
                    id = peer.deviceId ?: peer.serviceName, deviceId = peer.deviceId, name = peer.deviceName,
                    platform = platform, isPaired = paired, isNearby = true, isSendable = paired && current,
                    canPair = canPair, subtitle = subtitle, peer = peer
                )
            }

            val awayItems = trusted.filter { it.deviceId !in nearbyIds }.map { peer ->
                DeviceListItem(
                    id = peer.deviceId, deviceId = peer.deviceId, name = peer.deviceName,
                    platform = DevicePlatform.from(peer.platform), isPaired = true, isNearby = false,
                    isSendable = false, canPair = false, subtitle = "Paired · Not nearby", peer = null
                )
            }

            val (pairedNearby, unpairedNearby) = nearbyItems.partition { it.isPaired }
            return pairedNearby.sortedWith(byName) + unpairedNearby.sortedWith(byName) + awayItems.sortedWith(byName)
        }
    }
}

/**
 * Which device Send targets, by [DeviceListItem.id]. The user's pick wins whenever it is sendable;
 * otherwise the first sendable device stands in until the pick comes back. Nothing changes mid-send
 * or when discovery reports no devices at all (for example while the app is in the background).
 *
 * Unlike the Mac, with no sendable device the result is null rather than the stale selection, so an
 * unpaired or vanished device can never stay the send target.
 */
object DeviceSelection {
    fun resolve(current: String?, preferred: String?, items: List<DeviceListItem>, isSending: Boolean): String? {
        if (isSending || items.isEmpty()) return current
        val sendable = items.filter { it.isSendable }
        if (preferred != null && sendable.any { it.id == preferred }) return preferred
        if (current != null && sendable.any { it.id == current }) return current
        return sendable.firstOrNull()?.id
    }
}
