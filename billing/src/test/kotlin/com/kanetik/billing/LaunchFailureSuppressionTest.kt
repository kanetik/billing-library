package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LaunchFailureSuppressionTest {

    @Test
    fun `consume returns true for the same code arriving inside the window`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE)
        now += 12

        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isTrue()
    }

    @Test
    fun `consume returns true exactly at the 500ms boundary`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE)
        now += 500

        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isTrue()
    }

    @Test
    fun `consume returns false once the window has elapsed`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE)
        now += 501

        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isFalse()
    }

    @Test
    fun `consume returns false for a different response code`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE)
        now += 12

        assertThat(suppression.consume(BillingResponseCode.ITEM_ALREADY_OWNED)).isFalse()
    }

    @Test
    fun `consume is one-shot - a second call after an armed suppression was already consumed never suppresses`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE)
        now += 12
        suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)

        // A later callback carrying the same code, still well inside what would
        // have been the window, is no longer matched against a stale arm.
        now += 12
        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isFalse()
    }

    @Test
    fun `cancel clears a pending arm before it is ever consumed`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE)
        suppression.cancel()
        now += 12

        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isFalse()
    }

    @Test
    fun `consume with nothing armed returns false`() {
        val suppression = LaunchFailureSuppression { 1_000L }

        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isFalse()
    }
}
