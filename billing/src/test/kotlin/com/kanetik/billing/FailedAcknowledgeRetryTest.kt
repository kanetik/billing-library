@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kanetik.billing

import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.ConsumeParams
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Test

internal class FailedAcknowledgeRetryTest {

    @Test
    fun `a failed acknowledge schedules a sweep retry without waiting for a new connect`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(Op.ACKNOWLEDGE, BillingResponseCode.SERVICE_UNAVAILABLE)
            script(Op.QUERY_PURCHASES, BillingResponseCode.OK)
        }
        val repository = repositoryOver(play)
        val params = mockk<AcknowledgePurchaseParams>(relaxed = true)

        val outcome = timed { repository.acknowledgePurchase(params) }
        assertThat(outcome.result.exceptionOrNull())
            .isInstanceOf(BillingException.ServiceUnavailableException::class.java)
        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(0)

        advanceTimeBy(2001)

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(1)
    }

    @Test
    fun `a failed consume also schedules a sweep retry`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(Op.CONSUME, BillingResponseCode.SERVICE_UNAVAILABLE)
            script(Op.QUERY_PURCHASES, BillingResponseCode.OK)
        }
        val repository = repositoryOver(play)
        val params = mockk<ConsumeParams>(relaxed = true)

        val outcome = timed { repository.consumePurchase(params) }
        assertThat(outcome.result.exceptionOrNull())
            .isInstanceOf(BillingException.ServiceUnavailableException::class.java)

        advanceTimeBy(2001)

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(1)
    }

    @Test
    fun `a successful acknowledge does not schedule a sweep retry`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(Op.QUERY_PURCHASES, BillingResponseCode.OK)
        }
        val repository = repositoryOver(play)
        val params = mockk<AcknowledgePurchaseParams>(relaxed = true)

        repository.acknowledgePurchase(params)
        advanceTimeBy(2001)

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(0)
    }
}
