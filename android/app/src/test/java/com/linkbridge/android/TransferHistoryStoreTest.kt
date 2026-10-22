package com.linkbridge.android

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TransferHistoryStoreTest {
    /** Unsynchronised like SharedPreferences' read-then-apply, with a pause that widens the race. */
    private class SlowBacking : HistoryBacking {
        @Volatile private var value: String? = null
        override fun read(): String? = value.also { Thread.sleep(1) }
        override fun write(encoded: String) { value = encoded }
    }

    private fun entry(n: Int) = TransferHistoryEntry("file-$n", n.toLong(), "Mac", isOutgoing = n % 2 == 0, completedAt = n.toLong())

    /** Adds [count] entries from [threads] threads, each with its own store, as recordHistory does. */
    private fun addConcurrently(backing: HistoryBacking, threads: Int, count: Int) {
        val pool = Executors.newFixedThreadPool(threads)
        val start = CountDownLatch(1)
        repeat(count) { n ->
            pool.execute {
                start.await()
                TransferHistoryStore(backing).add(entry(n))
            }
        }
        start.countDown()
        pool.shutdown()
        check(pool.awaitTermination(30, TimeUnit.SECONDS))
    }

    @Test
    fun concurrentAddsFromSeparateStoresLoseNothing() {
        val backing = SlowBacking()
        addConcurrently(backing, threads = 8, count = 40)
        val names = TransferHistoryStore(backing).load().map { it.fileName }.toSet()
        assertEquals((0 until 40).map { "file-$it" }.toSet(), names)
    }

    @Test
    fun concurrentAddsStopAtTheCap() {
        val backing = SlowBacking()
        addConcurrently(backing, threads = 8, count = 80)
        val loaded = TransferHistoryStore(backing).load()
        assertEquals(TransferHistoryStore.MAX_ENTRIES, loaded.size)
        assertEquals(loaded.size, loaded.map { it.fileName }.toSet().size)
    }

    @Test
    fun newestEntryComesFirst() {
        val store = TransferHistoryStore(SlowBacking())
        store.add(entry(1))
        store.add(entry(2))
        assertEquals(listOf("file-2", "file-1"), store.load().map { it.fileName })
    }
}
