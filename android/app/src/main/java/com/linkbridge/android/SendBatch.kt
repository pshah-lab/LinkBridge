package com.linkbridge.android

import com.linkbridge.android.presentation.SendPhase

/**
 * Files picked together on the Send screen, queued in the service and sent one after another.
 * [finished] counts results so far; [failed] keeps the files that didn't go, so Retry resends
 * only those. Pure, so the queue rules are unit-tested.
 */
data class SendBatch(
    val files: List<StagedFile>,
    val finished: Int = 0,
    val failed: List<StagedFile> = emptyList(),
    val lastError: String? = null,
) {
    val isComplete: Boolean get() = finished >= files.size

    /** [fileName] with its place in the batch, when there is more than one file. */
    fun progressName(fileName: String): String =
        if (files.size > 1) "$fileName (${(finished + 1).coerceAtMost(files.size)} of ${files.size})" else fileName

    /** The file the service sends next, if any. */
    val next: StagedFile? get() = files.getOrNull(finished)

    /** Records the result for the file now sending. */
    fun record(error: String?): SendBatch {
        val current = next ?: return this
        return if (error == null) {
            copy(finished = finished + 1)
        } else {
            copy(finished = finished + 1, failed = failed + current, lastError = error)
        }
    }

    /** The phase once [isComplete]: Sent if every file went, else Failed naming what didn't. */
    fun outcome(): SendPhase =
        if (failed.isEmpty()) {
            SendPhase.Sent(summary(files).name)
        } else {
            val message = if (files.size == 1) lastError.orEmpty() else "${failed.size} of ${files.size} files didn't send"
            SendPhase.Failed(summary(failed).name, message)
        }

    companion object {
        /** The most files one send takes, from the picker or the share sheet. */
        const val MAX_FILES = 50

        /** One row for [files]: the file itself, or "N files" with their total size when known. */
        fun summary(files: List<StagedFile>): StagedFile {
            files.singleOrNull()?.let { return it }
            val size = if (files.all { it.size >= 0 }) files.sumOf { it.size } else -1L
            return StagedFile(uri = "", name = "${files.size} files", size = size, persistable = false)
        }
    }
}
