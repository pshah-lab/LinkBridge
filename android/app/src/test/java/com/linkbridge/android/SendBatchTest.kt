package com.linkbridge.android

import com.linkbridge.android.presentation.SendPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SendBatchTest {
    private val a = StagedFile("content://a", "a.txt", 10, persistable = true)
    private val b = StagedFile("content://b", "b.txt", 20, persistable = true)
    private val c = StagedFile("content://c", "c.txt", -1, persistable = false)

    @Test
    fun singleFileKeepsItsNameEverywhere() {
        val batch = SendBatch(listOf(a))
        assertEquals("a.txt", batch.progressName("a.txt"))
        assertEquals(a, SendBatch.summary(listOf(a)))
        assertEquals(SendPhase.Sent("a.txt"), batch.record(null).outcome())
        assertEquals(SendPhase.Failed("a.txt", "Connection refused"), batch.record("Connection refused").outcome())
    }

    @Test
    fun progressNameCountsFromOne() {
        val batch = SendBatch(listOf(a, b, c))
        assertEquals("a.txt (1 of 3)", batch.progressName("a.txt"))
        assertEquals("b.txt (2 of 3)", batch.record(null).progressName("b.txt"))
        assertEquals(b, batch.record(null).next)
    }

    @Test
    fun completesAfterOneResultPerFile() {
        var batch = SendBatch(listOf(a, b))
        batch = batch.record(null)
        assertFalse(batch.isComplete)
        batch = batch.record(null)
        assertTrue(batch.isComplete)
        assertNull(batch.next)
        assertEquals(SendPhase.Sent("2 files"), batch.outcome())
        // Results past the end are ignored.
        assertEquals(batch, batch.record("late"))
    }

    @Test
    fun failuresAreKeptAndNamed() {
        val batch = SendBatch(listOf(a, b, c)).record("x").record(null).record("y")
        assertEquals(listOf(a, c), batch.failed)
        assertEquals(SendPhase.Failed("2 files", "2 of 3 files didn't send"), batch.outcome())
    }

    @Test
    fun summaryTotalsSizeOnlyWhenEveryFileKnowsIt() {
        assertEquals(30L, SendBatch.summary(listOf(a, b)).size)
        assertEquals("2 files", SendBatch.summary(listOf(a, b)).name)
        assertEquals(-1L, SendBatch.summary(listOf(a, c)).size)
    }
}
