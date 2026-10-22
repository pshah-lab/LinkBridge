package com.linkbridge.android

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.linkbridge.android.LinkBridgeHub.SendState
import com.linkbridge.android.presentation.DeviceListItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The parts of the hub and stores the share sheet uses, so tests can fake them. */
interface SharePort {
    val peers: StateFlow<List<DiscoveredPeer>>
    val sendState: StateFlow<SendState?>
    /** One event per finished send; see [LinkBridgeHub.sendResults]. */
    val sendResults: Flow<SendState>
    /** Blocking; call off the main thread. */
    fun trusted(): List<TrustedPeer>
    /** Display names of the shared files, in order. Blocking; call off the main thread. */
    fun fileNames(): List<String>
    /** Queues shared file [index] in the service. False if the service is not running. */
    fun send(peer: DiscoveredPeer, trustedPeer: TrustedPeer, index: Int): Boolean
}

/** What the share sheet shows. */
sealed interface ShareStep {
    /** Nothing sendable was shared (no content:// streams from another app). */
    data object Unsupported : ShareStep
    /** Pick a device. */
    data object Choose : ShareStep
    /** [progress] is 0..1 for the current file, or null before its first byte count. */
    data class Sending(val text: String, val progress: Float?) : ShareStep
    data class Done(val text: String) : ShareStep
    /** Shown in coral: [title] says what happened, [message] why. */
    data class Failed(val title: String, val message: String) : ShareStep
}

data class ShareUiState(
    val fileCount: Int,
    /** Sendable devices only (paired and nearby). */
    val targets: List<DeviceListItem> = emptyList(),
    /** Whether any device is paired, sendable or not. */
    val hasPaired: Boolean = false,
    val step: ShareStep = if (fileCount == 0) ShareStep.Unsupported else ShareStep.Choose,
) {
    val header: String get() = ShareText.header(fileCount)
    val isSending: Boolean get() = step is ShareStep.Sending
}

/** The share sheet's copy, per the design spec. */
object ShareText {
    fun header(count: Int): String = if (count == 1) "Send 1 file" else "Send $count files"

    fun sending(index: Int, count: Int, fileName: String): String = "Sending $index of $count — $fileName"

    fun sent(count: Int, macName: String): String =
        if (count == 1) "Sent 1 file to $macName" else "Sent $count files to $macName"

    fun failedTitle(sent: Int, count: Int, macName: String): String =
        if (sent == 0) "Couldn't send to $macName" else "Sent $sent of $count files to $macName"

    const val NOT_RUNNING = "LinkBridge isn't running. Open LinkBridge and try again."
    const val UNSUPPORTED = "LinkBridge can only send files. Share a photo, video or document instead."
}

/**
 * Results so far for a send of [total] files. [index] is the 1-based file now sending.
 * Pure, so the counting and failure rules are unit-tested.
 */
data class ShareTally(
    val total: Int,
    val finished: Int = 0,
    val failed: Int = 0,
    val lastError: String? = null,
) {
    val isComplete: Boolean get() = finished + failed >= total
    val index: Int get() = (finished + failed + 1).coerceAtMost(total)

    fun record(result: SendState): ShareTally = when (result) {
        is SendState.Done -> copy(finished = finished + 1)
        is SendState.Failed -> copy(failed = failed + 1, lastError = result.message)
        is SendState.Progress -> this
    }

    fun refused(message: String): ShareTally = copy(failed = failed + 1, lastError = message)

    /** The final step once [isComplete]: Done if every file went, else Failed with the last error. */
    fun outcome(macName: String): ShareStep =
        if (failed == 0) {
            ShareStep.Done(ShareText.sent(finished, macName))
        } else {
            ShareStep.Failed(ShareText.failedTitle(finished, total, macName), lastError.orEmpty())
        }
}

/**
 * State for [ShareActivity]: the sendable devices, then one send of every shared file
 * through the service (queued at once, sent in turn by the service), counted as each
 * finishes.
 */
class ShareViewModel(
    private val port: SharePort,
    private val fileCount: Int,
    private val io: CoroutineDispatcher,
) : ViewModel() {
    private val trusted = MutableStateFlow<List<TrustedPeer>>(emptyList())
    private val names = MutableStateFlow<List<String>>(emptyList())
    private val step = MutableStateFlow<ShareStep>(ShareUiState(fileCount).step)

    private var tally = ShareTally(fileCount)
    private var macName = ""

    val state: StateFlow<ShareUiState> = combine(port.peers, trusted, step) { nearby, paired, current ->
        ShareUiState(
            fileCount = fileCount,
            targets = DeviceListItem.merge(nearby, paired).filter { it.isSendable },
            hasPaired = paired.isNotEmpty(),
            step = current,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ShareUiState(fileCount))

    init {
        viewModelScope.launch {
            val (paired, fileNames) = withContext(io) { port.trusted() to port.fileNames() }
            trusted.value = paired
            names.value = fileNames
        }
        // Subscribed before any send can start: results are not replayed.
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            port.sendResults.collect { result -> if (step.value is ShareStep.Sending) onResult(result) }
        }
        viewModelScope.launch {
            port.sendState.collect { state ->
                if (state is SendState.Progress && step.value is ShareStep.Sending) {
                    val progress = if (state.total > 0) (state.sent.toFloat() / state.total).coerceIn(0f, 1f) else null
                    step.value = ShareStep.Sending(ShareText.sending(tally.index, fileCount, state.fileName), progress)
                }
            }
        }
    }

    /** Sends every shared file to the device with row id [itemId]. Ignored unless choosing. */
    fun send(itemId: String) {
        if (step.value != ShareStep.Choose) return
        val item = state.value.targets.firstOrNull { it.id == itemId } ?: return
        val peer = item.peer ?: return
        val trustedPeer = trusted.value.firstOrNull { it.deviceId == item.deviceId } ?: return
        macName = item.name
        tally = ShareTally(fileCount)
        step.value = ShareStep.Sending(ShareText.sending(1, fileCount, nameOf(0)), progress = null)
        for (index in 0 until fileCount) {
            if (!port.send(peer, trustedPeer, index)) tally = tally.refused(ShareText.NOT_RUNNING)
        }
        if (tally.isComplete) step.value = tally.outcome(macName)
    }

    private fun onResult(result: SendState) {
        tally = tally.record(result)
        step.value = if (tally.isComplete) {
            tally.outcome(macName)
        } else {
            ShareStep.Sending(ShareText.sending(tally.index, fileCount, nameOf(tally.index - 1)), progress = null)
        }
    }

    private fun nameOf(index: Int): String = names.value.getOrNull(index) ?: "file"
}
