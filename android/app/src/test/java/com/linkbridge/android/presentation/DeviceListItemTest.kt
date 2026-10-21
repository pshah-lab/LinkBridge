package com.linkbridge.android.presentation

import com.linkbridge.android.DiscoveredPeer
import com.linkbridge.android.TrustedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from the Mac "Device list merge" checks, with the Android identity rules from the spec. */
class DeviceListItemTest {
    private fun peer(
        name: String, deviceId: String? = null, platform: String? = "macos", tlsPort: Int? = 49162
    ) = DiscoveredPeer(
        serviceName = "svc-$name", deviceId = deviceId, deviceName = name, platform = platform,
        host = "10.0.0.5", port = 49152, tlsPort = tlsPort
    )

    private fun trusted(name: String, deviceId: String) =
        TrustedPeer(deviceId, name, "macos", "10.0.0.5", 49152, authToken = null)

    @Test
    fun orderIsNearbyPairedThenNearbyUnpairedThenAway() {
        val items = DeviceListItem.merge(
            nearby = listOf(peer("Zed Mac", "a"), peer("unpaired Mac"), peer("Alpha Mac", "b"), peer("Beta Mac")),
            trusted = listOf(trusted("Zed Mac", "a"), trusted("Alpha Mac", "b"), trusted("Way Mac", "d"), trusted("away Mac", "e"))
        )
        assertEquals(
            listOf("Alpha Mac", "Zed Mac", "Beta Mac", "unpaired Mac", "away Mac", "Way Mac"),
            items.map { it.name }
        )
    }

    @Test
    fun idIsDeviceIdOrServiceName() {
        val items = DeviceListItem.merge(listOf(peer("Paired", "p-1"), peer("New")), listOf(trusted("Paired", "p-1")))
        assertEquals("p-1", items[0].id)
        assertEquals("svc-New", items[1].id)
        assertNull(items[1].deviceId)
    }

    @Test
    fun unpairedMacWithoutDeviceIdCanPair() {
        val nearby = peer("Mac")
        val item = DeviceListItem.merge(listOf(nearby), emptyList()).single()
        assertEquals(DevicePlatform.MAC, item.platform)
        assertEquals("Not paired", item.subtitle)
        assertTrue(item.canPair)
        assertFalse(item.isPaired)
        assertFalse(item.isSendable)
        assertTrue(item.isNearby)
        assertSame(nearby, item.peer)
    }

    @Test
    fun missingTlsPortNeedsAnUpdate() {
        val unpaired = DeviceListItem.merge(listOf(peer("Old Mac", tlsPort = null)), emptyList()).single()
        assertEquals("Needs a LinkBridge update", unpaired.subtitle)
        assertFalse(unpaired.canPair)
        assertFalse(unpaired.isSendable)

        val paired = DeviceListItem.merge(listOf(peer("Old Mac", "o-1", tlsPort = null)), listOf(trusted("Old Mac", "o-1"))).single()
        assertEquals("Needs a LinkBridge update", paired.subtitle)
        assertFalse(paired.canPair)
        assertFalse(paired.isSendable)
    }

    @Test
    fun pairedNearbyWithTlsIsSendable() {
        val item = DeviceListItem.merge(listOf(peer("Mac", "m-1")), listOf(trusted("Mac", "m-1"))).single()
        assertEquals("Paired · Nearby", item.subtitle)
        assertTrue(item.isPaired)
        assertTrue(item.isNearby)
        assertTrue(item.isSendable)
        assertFalse(item.canPair)
    }

    @Test
    fun trustedButNotNearbyIsAway() {
        val item = DeviceListItem.merge(emptyList(), listOf(trusted("Mac", "m-1"))).single()
        assertEquals("m-1", item.id)
        assertEquals("Paired · Not nearby", item.subtitle)
        assertTrue(item.isPaired)
        assertFalse(item.isNearby)
        assertFalse(item.isSendable)
        assertFalse(item.canPair)
        assertNull(item.peer)
    }

    @Test
    fun unpairedAndroidIsPairedFromTheMac() {
        val item = DeviceListItem.merge(listOf(peer("Phone", platform = "android")), emptyList()).single()
        assertEquals(DevicePlatform.ANDROID, item.platform)
        assertEquals("Pair from the Mac app", item.subtitle)
        assertFalse(item.canPair)
        assertFalse(item.isSendable)
    }

    @Test
    fun platformFromRawValue() {
        assertEquals(DevicePlatform.MAC, DevicePlatform.from("macos"))
        assertEquals(DevicePlatform.ANDROID, DevicePlatform.from("android"))
        assertEquals(DevicePlatform.IPHONE, DevicePlatform.from("ios"))
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.from("windows"))
        assertEquals(DevicePlatform.UNKNOWN, DevicePlatform.from(null))
    }

    @Test
    fun mergeDedupesSameIdentity() {
        val first = peer("Mac", "a")
        val items = DeviceListItem.merge(
            nearby = listOf(first, peer("Mac again", "a"), peer("New"), peer("New")),
            trusted = listOf(trusted("Mac", "a"), trusted("Away", "b"), trusted("Aardvark dup", "b"))
        )
        assertEquals(listOf("a", "svc-New", "b"), items.map { it.id })
        assertSame(first, items[0].peer)
        assertEquals("Away", items[2].name)
    }
}
