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
}
