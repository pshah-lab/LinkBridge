package com.linkbridge.android.offline

import com.linkbridge.android.DiscoveredPeer

/**
 * Peers that said hello, keyed by rid. Anyone on the hotspot can send a hello with a fresh rid, so
 * the table holds at most [capacity] entries (oldest refresh evicted) and forgets entries not
 * refreshed within [ttlMs]. Not thread-safe: use from one thread.
 */
class HelloPeerTable(
    private val capacity: Int = 16,
    private val ttlMs: Long = 5 * 60_000L,
    private val clock: () -> Long,
) {
    private class Entry(val peer: DiscoveredPeer, val at: Long)

    private val entries = linkedMapOf<String, Entry>() // insertion order = refresh order

    fun put(rid: String, peer: DiscoveredPeer) {
        entries.remove(rid)
        entries[rid] = Entry(peer, clock())
        prune()
        while (entries.size > capacity) entries.remove(entries.keys.first())
    }

    fun peers(): List<DiscoveredPeer> {
        prune()
        return entries.values.map { it.peer }
    }

    fun clear() = entries.clear()

    private fun prune() {
        val now = clock()
        entries.values.removeAll { now - it.at >= ttlMs }
    }
}
