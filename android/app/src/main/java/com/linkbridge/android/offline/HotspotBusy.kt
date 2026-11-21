package com.linkbridge.android.offline

import com.linkbridge.android.LinkBridgeHub.SendState
import com.linkbridge.android.presentation.PendingApproval
import com.linkbridge.android.presentation.ReceiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Whether the hotspot's idle timer should be paused: a transfer in either direction, an approval
 * prompt waiting for an answer, or pairing in progress.
 */
internal fun isOfflineBusy(
    receive: ReceiveState,
    send: SendState?,
    approvals: List<PendingApproval>,
    pairing: Boolean,
): Boolean = receive is ReceiveState.Receiving || send is SendState.Progress || approvals.isNotEmpty() || pairing

/**
 * Keeps [setBusy] equal to [isOfflineBusy] for as long as it runs. It lives with the service, which
 * owns sends and receives, so it works with no screen open (share-sheet sends, a closed app).
 * [pairing] is the one input only the UI knows. The hotspot's own state is an input too, so a new
 * session is told the current flag again ([setBusy] must be idempotent).
 */
class HotspotBusyDriver(
    scope: CoroutineScope,
    receive: Flow<ReceiveState>,
    send: Flow<SendState?>,
    approvals: Flow<List<PendingApproval>>,
    pairing: Flow<Boolean>,
    hotspot: Flow<HotspotState>,
    private val setBusy: (Boolean) -> Unit,
) {
    private val job: Job = scope.launch {
        combine(receive, send, approvals, pairing, hotspot) { r, s, a, p, _ -> isOfflineBusy(r, s, a, p) }
            .collect { setBusy(it) }
    }

    fun stop() = job.cancel()
}
