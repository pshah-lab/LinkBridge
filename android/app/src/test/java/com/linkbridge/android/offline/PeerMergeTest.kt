package com.linkbridge.android.offline

import com.linkbridge.android.DiscoveredPeer
import org.junit.Assert.assertEquals
import org.junit.Test

class PeerMergeTest {
    private fun peer(name: String, host: String, rid: String?, tags: String = "", deviceId: String? = null) =
        DiscoveredPeer(name, deviceId, name, "ios", host, 49153, 49153, rid, tags)

    private val match: (String, String) -> String? = { _, tags -> if (tags == "paired") "dev-1" else null }

    @Test fun mergeDedupesBonjourAndHello() {
        val bonjour = listOf(peer("iPhone", "192.168.43.5", "r1", "paired"), peer("Mac", "192.168.43.6", "r2"))
        val hello = listOf(
            peer("offline-r1", "192.168.43.5", "r1", "paired"),   // same rid and device
            peer("offline-r3", "192.168.43.7", "r3", "paired"),   // new rid, same paired device
            peer("offline-r2", "192.168.43.6", "r2"),             // same rid, unpaired
            peer("offline-r4", "192.168.43.8", "r4"),             // new
        )
        val merged = mergePeers(bonjour, hello, match)
        assertEquals(listOf("iPhone", "Mac", "offline-r4"), merged.map { it.serviceName })
    }

    @Test fun helloOnlyPeersAreKept() {
        val merged = mergePeers(emptyList(), listOf(peer("offline-r9", "10.0.0.2", "r9")), match)
        assertEquals(1, merged.size)
    }
}
