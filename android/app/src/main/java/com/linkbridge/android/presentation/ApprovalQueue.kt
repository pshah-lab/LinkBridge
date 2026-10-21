package com.linkbridge.android.presentation

import com.linkbridge.android.IncomingTransferRequest

/** An incoming file waiting for the user's answer. */
data class PendingApproval(val id: Int, val request: IncomingTransferRequest, val expiresAt: Long)

/** Pending approvals in arrival order. Immutable: every change returns a new queue. */
data class ApprovalQueue(val items: List<PendingApproval> = emptyList()) {
    /** The request that arrived first, which the approval sheet shows. */
    val oldest: PendingApproval? get() = items.firstOrNull()

    fun add(approval: PendingApproval): ApprovalQueue = copy(items = items + approval)

    fun resolve(id: Int): ApprovalQueue =
        if (items.none { it.id == id }) this else copy(items = items.filterNot { it.id == id })

    /** Drops every request whose deadline is at or before [now]. */
    fun expire(now: Long): ApprovalQueue = copy(items = items.filter { it.expiresAt > now })
}
