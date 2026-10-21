package com.linkbridge.android

import com.linkbridge.android.presentation.ApprovalQueue
import com.linkbridge.android.presentation.PendingApproval
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * The single resolver for incoming-file approvals. The in-app sheet, the notification
 * actions and the timeout all answer through [resolve]; the first answer wins.
 *
 * Thread-safe: [register] and [await] run on receive threads, [resolve] on the main thread
 * or from a service intent.
 */
class ApprovalCenter(
    private val clock: () -> Long,
    private val onWithdrawn: (id: Int) -> Unit
) {
    private val lock = Any()
    private var nextId = 1
    private var queue = ApprovalQueue()
    /** One answer slot per registered id, kept until [await] has read it. */
    private val answers = HashMap<Int, ArrayBlockingQueue<Boolean>>()

    private val _pending = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pending: StateFlow<List<PendingApproval>> = _pending.asStateFlow()

    fun register(request: IncomingTransferRequest): Int = synchronized(lock) {
        val id = nextId++
        answers[id] = ArrayBlockingQueue(1)
        queue = queue.add(
            PendingApproval(id, request, clock() + Timeouts.APPROVAL_TIMEOUT_SECONDS * 1000)
        )
        _pending.value = queue.items
        id
    }

    /** Blocks until [id] is answered. No answer within [timeoutSeconds] declines it. */
    fun await(id: Int, timeoutSeconds: Long): Boolean {
        val slot = synchronized(lock) { answers[id] } ?: return false
        try {
            slot.poll(timeoutSeconds, TimeUnit.SECONDS)?.let { return it }
            // Timed out. If another caller answered in the meantime, its answer stands.
            resolve(id, false)
            return slot.poll() ?: false
        } finally {
            synchronized(lock) { answers.remove(id) }
        }
    }

    /** Answers [id]. Returns false if it was already answered, timed out or never existed. */
    fun resolve(id: Int, accept: Boolean): Boolean {
        val slot = synchronized(lock) {
            if (queue.items.none { it.id == id }) return false
            queue = queue.resolve(id)
            _pending.value = queue.items
            answers[id]
        }
        slot?.offer(accept)
        onWithdrawn(id)
        return true
    }

    /** Declines everything pending, e.g. when the service stops. */
    fun declineAll() {
        val ids = synchronized(lock) { queue.items.map { it.id } }
        ids.forEach { resolve(it, false) }
    }
}
