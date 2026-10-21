package com.linkbridge.android

/**
 * Transfer timeouts the Mac depends on. The Mac gives up after 110 s, so the phone's
 * reads must outlast it; TimeoutsTest guards these values.
 */
object Timeouts {
    /** `/pair/finalize` blocks until the Mac user confirms the code. */
    const val PAIR_FINALIZE_READ_TIMEOUT_MS = 120_000

    /** The receiver may wait for its user to accept the file before replying. */
    const val UPLOAD_SOCKET_TIMEOUT_MS = 120_000

    /** How long an incoming-file request waits for an answer before it is declined. */
    const val APPROVAL_TIMEOUT_SECONDS = 60L
}
