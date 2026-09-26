package com.kanetik.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultBillingRepositoryFeatureSupportTest {

    @Test
    fun `FEATURE_NOT_SUPPORTED returns false on the first attempt`() = runTest {
        val play = FakePlay().apply { script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED) }
        val repo = repositoryOver(play)

        val result = repo.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)

        assertThat(result).isFalse()
        assertThat(play.calls(Op.IS_FEATURE_SUPPORTED)).isEqualTo(1)
    }

    @Test
    fun `SERVICE_DISCONNECTED is retried and recovers to true`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.SERVICE_DISCONNECTED, BillingResponseCode.OK)
        }
        val repo = repositoryOver(play)

        val result = repo.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)

        assertThat(result).isTrue()
        assertThat(play.calls(Op.IS_FEATURE_SUPPORTED)).isEqualTo(2)
    }

    @Test
    fun `exhausted retries on a transient code throw instead of reporting unsupported`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.SERVICE_UNAVAILABLE)
        }
        val repo = repositoryOver(play)

        val outcome = runCatching { repo.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS) }

        assertThat(outcome.exceptionOrNull()).isInstanceOf(BillingException.ServiceUnavailableException::class.java)
    }
}
