package com.kanetik.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.factory.DefaultBillingClientFactory
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Ignore
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultBillingRepositoryReconnectTest {

    @Test
    fun `operations in one session share a single startConnection`() = runTest {
        val play = FakePlay()
        val repo = repositoryOver(play)

        repo.perform(Op.QUERY_PURCHASES)
        repo.perform(Op.QUERY_PRODUCT_DETAILS)
        repo.perform(Op.ACKNOWLEDGE)

        assertThat(play.startConnectionCount).isEqualTo(1)
    }

    @Test
    fun `onBillingServiceDisconnected leaves reconnection to PBL and the next operation succeeds`() = runTest {
        val play = FakePlay()
        val repo = repositoryOver(play)
        repo.perform(Op.QUERY_PURCHASES)

        play.stateListeners.single().onBillingServiceDisconnected()
        val result = runCatching { repo.perform(Op.QUERY_PURCHASES) }

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(play.startConnectionCount).isEqualTo(1)
    }

    @Test
    fun `SERVICE_DISCONNECTED mid-session retries on the same client without a new startConnection`() = runTest {
        val play = FakePlay().apply {
            script(Op.QUERY_PURCHASES, BillingResponseCode.SERVICE_DISCONNECTED, BillingResponseCode.OK)
        }
        val repo = repositoryOver(play)

        val result = runCatching { repo.perform(Op.QUERY_PURCHASES) }

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(play.clients).hasSize(1)
        assertThat(play.calls(Op.QUERY_PURCHASES, play.clients.single())).isEqualTo(2)
        assertThat(play.startConnectionCount).isEqualTo(1)
    }

    @Test
    fun `the idle stop ends the client`() = runTest {
        val play = FakePlay()
        val repo = repositoryOver(play)
        repo.perform(Op.QUERY_PURCHASES)

        advanceTimeBy(60_001)
        runCurrent()

        assertThat(play.endedClients).containsExactly(play.clients.single())
    }

    @Ignore("#53: replay hands out the ended client after the idle stop")
    @Test
    fun `an operation after the idle stop runs on a fresh client`() = runTest {
        val play = FakePlay()
        val repo = repositoryOver(play)
        repo.perform(Op.QUERY_PURCHASES)
        advanceTimeBy(60_001)
        runCurrent()

        val result = runCatching { repo.perform(Op.QUERY_PURCHASES) }

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(play.calls(Op.QUERY_PURCHASES, play.clients.first())).isEqualTo(1)
    }

    @Ignore("#53: a terminal connect failure is replayed while connectToBilling is collected")
    @Test
    fun `a terminal connect failure does not stick while connectToBilling is collected`() = runTest {
        val play = FakePlay().apply { connectCodes.addLast(BillingResponseCode.BILLING_UNAVAILABLE) }
        val repo = repositoryOver(play)
        backgroundScope.launch { repo.connectToBilling().collect { } }
        runCurrent()
        val first = runCatching { repo.perform(Op.QUERY_PURCHASES) }

        advanceTimeBy(5_000)
        val later = runCatching { repo.perform(Op.QUERY_PURCHASES) }

        assertThat(first.exceptionOrNull()).isInstanceOf(BillingException.BillingUnavailableException::class.java)
        assertThat(later.exceptionOrNull()).isNull()
    }

    @Ignore("#53: a terminal connect failure survives the idle stop in replay")
    @Test
    fun `a terminal connect failure does not survive the idle stop`() = runTest {
        val play = FakePlay().apply { connectCodes.addLast(BillingResponseCode.BILLING_UNAVAILABLE) }
        val repo = repositoryOver(play)
        runCatching { repo.perform(Op.QUERY_PURCHASES) }

        advanceTimeBy(60_001)
        runCurrent()
        val later = runCatching { repo.perform(Op.QUERY_PURCHASES) }

        assertThat(later.exceptionOrNull()).isNull()
    }

    @Test
    fun `a connect that never calls back fails the operation at 30s without calling Play`() = runTest {
        val play = FakePlay().apply { connectCodes.addLast(null) }
        val repo = repositoryOver(play)

        val outcome = timed { repo.perform(Op.QUERY_PURCHASES) }

        assertThat(outcome.result.exceptionOrNull())
            .isInstanceOf(BillingException.ServiceUnavailableException::class.java)
        assertThat(outcome.elapsedMs).isEqualTo(30_000L)
        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(0)
    }

    @Ignore("unfiled: a startConnection that never calls back is never abandoned or retried")
    @Test
    fun `after a hung connect times out the next operation starts a fresh connection`() = runTest {
        val play = FakePlay().apply { connectCodes.addLast(null) }
        val repo = repositoryOver(play)
        runCatching { repo.perform(Op.QUERY_PURCHASES) }

        val later = runCatching { repo.perform(Op.QUERY_PURCHASES) }

        assertThat(later.exceptionOrNull()).isNull()
        assertThat(play.startConnectionCount).isEqualTo(2)
    }

    @Test
    fun `the default client factory enables automatic service reconnection`() {
        val builder = mockk<BillingClient.Builder>(relaxed = true)
        every { builder.enablePendingPurchases(any()) } returns builder
        every { builder.enableAutoServiceReconnection() } returns builder
        every { builder.setListener(any()) } returns builder
        mockkStatic(BillingClient::class)
        try {
            every { BillingClient.newBuilder(any()) } returns builder

            DefaultBillingClientFactory().createBillingClient(mockk<Context>(relaxed = true)) { _, _ -> }

            verify { builder.enableAutoServiceReconnection() }
        } finally {
            unmockkStatic(BillingClient::class)
        }
    }
}
