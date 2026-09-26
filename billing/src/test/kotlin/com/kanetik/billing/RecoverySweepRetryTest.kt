@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

internal class RecoverySweepRetryTest {

    private fun TestScope.sweepOver(vararg purchaseQueryCodes: Int): FakePlay {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(Op.QUERY_PURCHASES, *purchaseQueryCodes)
        }
        val storage = storageOver(play, recoverPurchasesOnConnect = true)
        backgroundScope.launch { storage.connectionFlow.collect {} }
        runCurrent()
        return play
    }

    @Test
    fun `sweep retries a transient purchase query with exponential backoff until it succeeds`() = runTest {
        val play = sweepOver(BillingResponseCode.NETWORK_ERROR, BillingResponseCode.NETWORK_ERROR, BillingResponseCode.OK)

        advanceTimeBy(2000L + 4000L - 1)
        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(2)

        advanceTimeBy(2)
        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(3)
    }

    @Test
    fun `sweep retries a disconnect with the background profile rather than a short fixed delay`() = runTest {
        val play = sweepOver(BillingResponseCode.SERVICE_DISCONNECTED, BillingResponseCode.OK)

        advanceTimeBy(2000L - 1)
        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(1)

        advanceTimeBy(2)
        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(2)
    }

    @Test
    fun `sweep gives up after five attempts on a persistent transient failure`() = runTest {
        val play = sweepOver(BillingResponseCode.SERVICE_UNAVAILABLE)

        advanceTimeBy(ONE_HOUR_MS)

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(5)
    }

    @Test
    fun `sweep does not retry a terminal purchase query failure`() = runTest {
        val play = sweepOver(BillingResponseCode.DEVELOPER_ERROR)

        advanceTimeBy(ONE_HOUR_MS)

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(1)
    }

    private companion object {
        const val ONE_HOUR_MS = 3_600_000L
    }
}
