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
    /** Pending ids that already have a notification, so none is posted twice. */
    private val notified = HashSet<Int>()

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

    /**
     * Blocks until [id] is answered. No answer within [timeoutSeconds] declines it, and so
     * does an interrupt (the receive server shutting down), which is then re-asserted.
     * The result always equals the answer of the [resolve] call that won.
     */
    fun await(id: Int, timeoutSeconds: Long): Boolean {
        val slot = synchronized(lock) { answers[id] } ?: return false
        try {
            val answer = try {
                slot.poll(timeoutSeconds, TimeUnit.SECONDS)
            } catch (interrupted: InterruptedException) {
                Thread.currentThread().interrupt()
                null
            }
            if (answer != null) return answer
            // Timed out or interrupted: decline, unless another caller has already won.
            resolve(id, false)
            // The winner offered its answer while holding the lock, so it is in the slot now.
            return slot.poll() ?: false
        } finally {
            synchronized(lock) { answers.remove(id) }
        }
    }

    /** Answers [id]. Returns false if it was already answered, timed out or never existed. */
    fun resolve(id: Int, accept: Boolean): Boolean {
        synchronized(lock) {
            if (queue.items.none { it.id == id }) return false
            queue = queue.resolve(id)
            notified.remove(id)
            _pending.value = queue.items
            // Offer under the lock so the decision and the answer are one atomic step.
            answers[id]?.offer(accept)
        }
        onWithdrawn(id)
        return true
    }

    /**
     * Declines [id] when nobody will [await] it (no way to ask the user), and drops its
     * answer slot, which otherwise only [await] removes.
     */
    fun declineUnasked(id: Int) {
        resolve(id, false)
        synchronized(lock) { answers.remove(id) }
    }

    /**
     * Claims the notification for [id]. Returns true once while [id] is pending, so the
     * caller that gets true posts it; false if it is already posted or [id] is answered.
     */
    fun claimNotification(id: Int): Boolean = synchronized(lock) {
        queue.items.any { it.id == id } && notified.add(id)
    }

    /**
     * Claims the notification for every pending request that has none yet (the app is
     * leaving the screen while its sheet still asks) and returns them, oldest first.
     */
    fun claimUnnotified(): List<PendingApproval> = synchronized(lock) {
        queue.items.filter { notified.add(it.id) }
    }

    fun isPending(id: Int): Boolean = synchronized(lock) { queue.items.any { it.id == id } }

    /** Answer slots still held; for tests. */
    internal fun openSlots(): Int = synchronized(lock) { answers.size }

    /** Declines everything pending, e.g. when the service stops. */
    fun declineAll() {
        val ids = synchronized(lock) { queue.items.map { it.id } }
        ids.forEach { resolve(it, false) }
    }
}
