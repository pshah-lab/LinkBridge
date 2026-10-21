package com.linkbridge.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ApprovalCenterTest {
    private val request = IncomingTransferRequest("Mac", "mac-1", "photo.jpg", 1024L)
    private val withdrawn: MutableList<Int> = Collections.synchronizedList(mutableListOf())
    private val center = ApprovalCenter(clock = { 1_000L }, onWithdrawn = { withdrawn += it })

    /** Runs `await` on a background thread, as the receive server does. */
    private fun awaitInBackground(id: Int, timeoutSeconds: Long = 5): Pair<CountDownLatch, AtomicBoolean> {
        val done = CountDownLatch(1)
        val result = AtomicBoolean()
        Thread {
            result.set(center.await(id, timeoutSeconds))
            done.countDown()
        }.start()
        return done to result
    }

    @Test
    fun registerAddsPendingWithExpiry() {
        val id = center.register(request)
        val pending = center.pending.value.single()
        assertEquals(id, pending.id)
        assertEquals(request, pending.request)
        assertEquals(1_000L + Timeouts.APPROVAL_TIMEOUT_SECONDS * 1000, pending.expiresAt)
    }

    @Test
    fun firstAnswerWins() {
        val id = center.register(request)
        val (done, result) = awaitInBackground(id)
        assertTrue(center.resolve(id, true))
        assertFalse(center.resolve(id, false))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(result.get())
    }

    @Test
    fun answerBeforeAwaitIsKept() {
        val id = center.register(request)
        assertTrue(center.resolve(id, true))
        assertTrue(center.await(id, 5))
    }

    @Test
    fun notificationAnswerRemovesFromPending() {
        val id = center.register(request)
        val other = center.register(request.copy(fileName = "other.txt"))
        val (done, result) = awaitInBackground(id)
        assertTrue(center.resolve(id, false))
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertFalse(result.get())
        assertEquals(listOf(other), center.pending.value.map { it.id })
        assertEquals(listOf(id), withdrawn.toList())
    }

    @Test
    fun timeoutDeclinesAndWithdraws() {
        val id = center.register(request)
        assertFalse(center.await(id, 0))
        assertTrue(center.pending.value.isEmpty())
        assertEquals(listOf(id), withdrawn.toList())
        assertFalse(center.resolve(id, true))
        assertEquals(listOf(id), withdrawn.toList())
    }

    @Test
    fun declineAllClearsPending() {
        val first = center.register(request)
        val second = center.register(request.copy(fileName = "b.txt"))
        val (doneFirst, resultFirst) = awaitInBackground(first)
        val (doneSecond, resultSecond) = awaitInBackground(second)
        center.declineAll()
        assertTrue(doneFirst.await(5, TimeUnit.SECONDS))
        assertTrue(doneSecond.await(5, TimeUnit.SECONDS))
        assertFalse(resultFirst.get())
        assertFalse(resultSecond.get())
        assertTrue(center.pending.value.isEmpty())
        assertEquals(setOf(first, second), withdrawn.toSet())
        assertEquals(2, withdrawn.size)
        assertFalse(center.resolve(first, true))
    }

    @Test
    fun idsAreUnique() {
        assertTrue(center.register(request) != center.register(request))
    }
}
