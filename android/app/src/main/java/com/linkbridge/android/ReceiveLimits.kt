package com.linkbridge.android

/** Checks made before asking the user to accept a file. Same margin as the Mac. */
object ReceiveLimits {
    /** Free space to leave after a transfer, so receiving never fills the storage. */
    const val FREE_SPACE_MARGIN = 512L * 1024 * 1024

    /** True if [bytes] fit in [available] with the margin to spare. Unknown space (< 0) is allowed. */
    fun hasRoom(bytes: Long, available: Long): Boolean =
        available < 0 || bytes <= available - FREE_SPACE_MARGIN

    /** The plaintext can't be larger than the encrypted body that carries it. */
    fun sizeIsPlausible(bytes: Long, contentLength: Long): Boolean = bytes in 0..contentLength

    /** How long a refused, authenticated sender may keep streaming before the connection is closed. */
    const val REFUSAL_DRAIN_MS = 60_000L

    /**
     * Reads and discards up to [remaining] body bytes, stopping at end of stream or [deadlineMillis].
     * Senders write the whole body before reading; closing with unread data resets the connection
     * and loses the reply. Returns the bytes discarded.
     */
    fun drain(
        input: java.io.InputStream, remaining: Long, deadlineMillis: Long,
        now: () -> Long = System::currentTimeMillis,
    ): Long {
        val buffer = ByteArray(64 * 1024)
        var discarded = 0L
        while (discarded < remaining && now() < deadlineMillis) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining - discarded).toInt())
            if (read < 0) break
            discarded += read
        }
        return discarded
    }
}
