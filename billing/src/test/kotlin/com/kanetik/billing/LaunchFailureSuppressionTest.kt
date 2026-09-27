package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LaunchFailureSuppressionTest {

    @Test
    fun `consume returns true for the same code arriving inside the window`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        val attempt = suppression.cancel()
        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE, attempt)
        now += 12

        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isTrue()
    }

    @Test
    fun `consume returns true exactly at the 500ms boundary`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        val attempt = suppression.cancel()
        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE, attempt)
        now += 500

        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isTrue()
    }

    @Test
    fun `consume returns false once the window has elapsed`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        val attempt = suppression.cancel()
        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE, attempt)
        now += 501

        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isFalse()
    }

    @Test
    fun `consume returns false for a different response code`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        val attempt = suppression.cancel()
        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE, attempt)
        now += 12

        assertThat(suppression.consume(BillingResponseCode.ITEM_ALREADY_OWNED)).isFalse()
    }

    @Test
    fun `consume is one-shot - a second call after an armed suppression was already consumed never suppresses`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        val attempt = suppression.cancel()
        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE, attempt)
        now += 12
        suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)

        // A later callback carrying the same code, still well inside what would
        // have been the window, is no longer matched against a stale arm.
        now += 12
        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isFalse()
    }

    @Test
    fun `consume for a non-matching code inside the window leaves the arm intact for a later matching echo`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        val attempt = suppression.cancel()
        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE, attempt)
        now += 12
        assertThat(suppression.consume(BillingResponseCode.ITEM_ALREADY_OWNED)).isFalse()

        now += 12
        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isTrue()
    }

    @Test
    fun `cancel clears a pending arm before it is ever consumed`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        val attempt = suppression.cancel()
        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE, attempt)
        suppression.cancel()
        now += 12

        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isFalse()
    }

    @Test
    fun `arm is a no-op once a newer attempt has already started`() {
        // Models two overlapping launchFlow calls: the first captures its attempt
        // token, a second call starts (and cancels) before the first's failure
        // arms suppression - so the first's arm must not take effect against the
        // now-current attempt.
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        val staleAttempt = suppression.cancel()
        suppression.cancel()
        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE, staleAttempt)
        now += 12

        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isFalse()
    }

    @Test
    fun `arm still takes effect when no newer attempt has started since`() {
        var now = 1_000L
        val suppression = LaunchFailureSuppression { now }

        val attempt = suppression.cancel()
        suppression.arm(BillingResponseCode.BILLING_UNAVAILABLE, attempt)
        now += 12

        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isTrue()
    }

    @Test
    fun `consume with nothing armed returns false`() {
        val suppression = LaunchFailureSuppression { 1_000L }

        assertThat(suppression.consume(BillingResponseCode.BILLING_UNAVAILABLE)).isFalse()
    }
}
