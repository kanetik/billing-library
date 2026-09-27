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
    fun `sweep retries the whole sweep once more, for ten attempts total, on a persistent transient failure`() = runTest {
        val play = sweepOver(BillingResponseCode.SERVICE_UNAVAILABLE)

        advanceTimeBy(ONE_HOUR_MS)

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(10)
    }

    @Test
    fun `sweep succeeds on the outer retry after the first round's five attempts exhaust`() = runTest {
        val play = sweepOver(
            BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.SERVICE_UNAVAILABLE,
            BillingResponseCode.OK
        )

        advanceTimeBy(35_000L)

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(6)
    }

    @Test
    fun `sweep does not attempt a third round after the outer retry also fails`() = runTest {
        val play = sweepOver(BillingResponseCode.SERVICE_UNAVAILABLE)

        advanceTimeBy(ONE_HOUR_MS)
        val callsAfterOneHour = play.calls(Op.QUERY_PURCHASES)
        advanceTimeBy(ONE_HOUR_MS)

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(callsAfterOneHour)
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
