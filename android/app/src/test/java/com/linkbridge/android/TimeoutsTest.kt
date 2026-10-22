package com.linkbridge.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimeoutsTest {
    // The Mac gives up at 110 s; Android must wait longer.
    @Test fun finalizeOutlastsMac() = assertTrue(Timeouts.PAIR_FINALIZE_READ_TIMEOUT_MS >= 120_000)
    @Test fun uploadOutlastsMac() = assertTrue(Timeouts.UPLOAD_SOCKET_TIMEOUT_MS >= 120_000)
    @Test fun approvalIsSixtySeconds() = assertEquals(60L, Timeouts.APPROVAL_TIMEOUT_SECONDS)
}
