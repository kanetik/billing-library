package com.kanetik.billing.logging

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BillingLoggerTest {

    @Test
    fun `Noop discards every message without throwing`() {
        val logger = BillingLogger.Noop
        // No assertion needed — the contract is "doesn't throw".
        logger.d("debug")
        logger.d("debug with throwable", RuntimeException("ignored"))
        logger.w("warn")
        logger.w("warn with throwable", IllegalStateException("ignored"))
        logger.e("error")
        logger.e("error with throwable", Error("ignored"))
    }

    @Test
    fun `Noop and companion property point to the same singleton`() {
        // Sanity: BillingLogger.Noop must be a stable singleton, not constructed afresh.
        assertThat(BillingLogger.Noop).isSameInstanceAs(BillingLogger.Noop)
    }

    // The Android logger's behavior depends on android.util.Log, which is a stub
    // in pure-JVM unit tests. We don't try to verify its output here — that's
    // what the :sample module's instrumented test will exercise, and the v0.2.0
    // :billing-testing artifact will make easier via Robolectric.
}
