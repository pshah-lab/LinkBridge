package com.linkbridge.android.presentation

import com.linkbridge.android.IncomingTransferRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApprovalQueueTest {
    private fun approval(id: Int, expiresAt: Long) =
        PendingApproval(id, IncomingTransferRequest("Mac", "mac-1", "file$id.txt", 10L), expiresAt)

    @Test
    fun keepsArrivalOrderAndOldestIsFirst() {
        val queue = ApprovalQueue().add(approval(2, 500)).add(approval(1, 100))
        assertEquals(listOf(2, 1), queue.items.map { it.id })
        assertEquals(2, queue.oldest?.id)
    }

    @Test
    fun emptyQueueHasNoOldest() {
        assertNull(ApprovalQueue().oldest)
    }

    @Test
    fun resolveRemovesOnlyThatId() {
        val queue = ApprovalQueue().add(approval(1, 100)).add(approval(2, 200)).resolve(1)
        assertEquals(listOf(2), queue.items.map { it.id })
        assertEquals(queue, queue.resolve(99))
    }

    @Test
    fun expireDropsItemsAtOrPastTheirDeadline() {
        val queue = ApprovalQueue()
            .add(approval(1, 100))
            .add(approval(2, 200))
            .add(approval(3, 300))
            .expire(now = 200)
        assertEquals(listOf(3), queue.items.map { it.id })
    }
}
