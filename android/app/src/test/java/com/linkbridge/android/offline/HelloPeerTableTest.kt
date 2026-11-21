package com.linkbridge.android.offline

import com.linkbridge.android.DiscoveredPeer
import org.junit.Assert.assertEquals
import org.junit.Test

class HelloPeerTableTest {
    private var now = 0L
    private fun peer(rid: String) = DiscoveredPeer("offline-$rid", null, rid, "ios", "10.0.0.2", 1, 1, rid)
    private val table = HelloPeerTable(clock = { now })

    @Test fun capsAt16EvictingTheOldest() {
        for (i in 1..20) { now += 1; table.put("r$i", peer("r$i")) }
        assertEquals((5..20).map { "r$it" }, table.peers().map { it.rid })
    }

    @Test fun refreshKeepsAnEntryFromBeingEvicted() {
        for (i in 1..16) table.put("r$i", peer("r$i"))
        table.put("r1", peer("r1"))
        table.put("new", peer("new"))
        assertEquals(true, table.peers().any { it.rid == "r1" })
        assertEquals(false, table.peers().any { it.rid == "r2" })
    }

    @Test fun expiresEntriesNotRefreshedWithinFiveMinutes() {
        table.put("old", peer("old"))
        now += 4 * 60_000
        table.put("fresh", peer("fresh"))
        now += 61_000
        assertEquals(listOf("fresh"), table.peers().map { it.rid })
        table.put("fresh", peer("fresh"))
        now += 4 * 60_000
        assertEquals(listOf("fresh"), table.peers().map { it.rid })
    }
}
