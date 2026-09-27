package com.kanetik.billing

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.QueryPurchasesParams
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.factory.DefaultBillingClientFactory
import com.kanetik.billing.logging.BillingLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
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
    fun `a retry after the idle stop ended the client runs on a fresh client`() = runTest {
        val play = FakePlay()
        val repo = repositoryOver(play)
        repo.perform(Op.ACKNOWLEDGE)
        val retired = play.clients.single()
        var held: PurchasesResponseListener? = null
        every { retired.queryPurchasesAsync(any<QueryPurchasesParams>(), any()) } answers {
            val listener = secondArg<PurchasesResponseListener>()
            if (held == null) held = listener
            else listener.onQueryPurchasesResponse(billingResult(BillingResponseCode.SERVICE_DISCONNECTED), emptyList())
        }
        val op = backgroundScope.async { runCatching { repo.perform(Op.QUERY_PURCHASES) } }
        runCurrent()

        advanceTimeBy(60_001)
        runCurrent()
        held!!.onQueryPurchasesResponse(billingResult(BillingResponseCode.SERVICE_DISCONNECTED), emptyList())

        assertThat(op.await().exceptionOrNull()).isNull()
        assertThat(play.endedClients).contains(retired)
        assertThat(play.calls(Op.QUERY_PURCHASES, play.clients.last())).isEqualTo(1)
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

    @Test
    fun `the idle stop retires the client`() = runTest {
        val play = FakePlay()
        val storage = storageOver(play)
        storage.connectionFlow.filterNotNull().first()
        val liveBefore = storage.isLive(play.clients.single())

        advanceTimeBy(60_001)
        runCurrent()

        assertThat(liveBefore).isTrue()
        assertThat(storage.isLive(play.clients.single())).isFalse()
    }

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

    @Test
    fun `a terminal connect failure does not stick while connectToBilling is collected`() = runTest {
        val play = FakePlay().apply { connectCodes.addLast(BillingResponseCode.BILLING_UNAVAILABLE) }
        val repo = repositoryOver(play)
        backgroundScope.launch { repo.connectToBilling().collect { } }
        runCurrent()

        val result = runCatching { repo.perform(Op.QUERY_PURCHASES) }

        assertThat(result.exceptionOrNull()).isNull()
        assertThat(play.startConnectionCount).isEqualTo(2)
    }

    @Test
    fun `a connect failure fails the operation whose connect produced it without a second connect`() = runTest {
        val play = FakePlay().apply { connectCodes.addLast(BillingResponseCode.BILLING_UNAVAILABLE) }
        val repo = repositoryOver(play)

        val result = runCatching { repo.perform(Op.QUERY_PURCHASES) }
        runCurrent()

        assertThat(result.exceptionOrNull()).isInstanceOf(BillingException.BillingUnavailableException::class.java)
        assertThat(play.startConnectionCount).isEqualTo(1)
    }

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
    fun `a new connectToBilling subscriber after a terminal failure triggers a fresh connect`() = runTest {
        val play = FakePlay().apply { connectCodes.addLast(BillingResponseCode.BILLING_UNAVAILABLE) }
        val repo = repositoryOver(play)
        backgroundScope.launch { repo.connectToBilling().collect { } }
        runCurrent()

        val result = repo.connectToBilling().first { it is BillingConnectionResult.Success }

        assertThat(result).isEqualTo(BillingConnectionResult.Success)
        assertThat(play.startConnectionCount).isEqualTo(2)
    }

    @Test
    fun `operations waiting on a reconnect get its result instead of the replayed failure`() = runTest {
        val play = FakePlay().apply {
            connectCodes.addLast(BillingResponseCode.BILLING_UNAVAILABLE)
            connectCodes.addLast(null)
        }
        val repo = repositoryOver(play)
        backgroundScope.launch { repo.connectToBilling().collect { } }
        runCurrent()

        val first = backgroundScope.async { runCatching { repo.perform(Op.QUERY_PURCHASES) } }
        val second = backgroundScope.async { runCatching { repo.perform(Op.QUERY_PURCHASES) } }
        runCurrent()
        val stillWaiting = first.isActive && second.isActive
        play.stateListeners.last().onBillingSetupFinished(billingResult(BillingResponseCode.OK))

        assertThat(stillWaiting).isTrue()
        assertThat(first.await().exceptionOrNull()).isNull()
        assertThat(second.await().exceptionOrNull()).isNull()
        assertThat(play.startConnectionCount).isEqualTo(2)
    }

    @Test
    fun `a ready held client is reused without a new startConnection`() = runTest {
        val play = FakePlay()
        val repo = repositoryOver(play)
        backgroundScope.launch { repo.connectToBilling().collect { } }
        runCurrent()

        repo.perform(Op.QUERY_PURCHASES)
        repo.connectToBilling().first()

        assertThat(play.startConnectionCount).isEqualTo(1)
    }

    @Test
    fun `connectToBilling replays nothing after the idle stop`() = runTest {
        val play = FakePlay()
        val repo = repositoryOver(play)
        repo.connectToBilling().first()

        advanceTimeBy(120_001)
        runCurrent()

        assertThat(repo.connectToBilling().replayCache).isEmpty()
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

    @Test
    fun `a connect that never calls back logs the timeout exactly once`() = runTest {
        val play = FakePlay().apply { connectCodes.addLast(null) }
        val captor = CapturingLogger()
        val repo = DefaultBillingRepository(
            billingClientStorage = storageOver(play),
            logger = captor,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            uiDispatcher = UnconfinedTestDispatcher(testScheduler)
        )

        runCatching { repo.perform(Op.QUERY_PURCHASES) }

        assertThat(captor.warnings).hasSize(1)
        assertThat(captor.errors).isEmpty()
    }

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
    fun `a hung connect ends its client when the setup times out`() = runTest {
        val play = FakePlay().apply { connectCodes.addLast(null) }
        val repo = repositoryOver(play)

        runCatching { repo.perform(Op.QUERY_PURCHASES) }
        runCurrent()

        assertThat(play.endedClients).contains(play.clients.first())
        assertThat(play.clients).hasSize(1)
    }

    @Test
    fun `a hung connect is retried on a fresh client`() = runTest {
        val play = FakePlay().apply { connectCodes.addLast(null) }
        val repo = repositoryOver(play)
        runCatching { repo.perform(Op.QUERY_PURCHASES) }

        repo.perform(Op.QUERY_PURCHASES)

        assertThat(play.clients).hasSize(2)
        assertThat(play.calls(Op.QUERY_PURCHASES, play.clients.last())).isEqualTo(1)
    }

    @Test
    fun `connectToBilling reports an error once every connect attempt hangs`() = runTest {
        val play = FakePlay().apply { repeat(ConnectionRetryPolicy.DEFAULT_MAX_ATTEMPTS) { connectCodes.addLast(null) } }
        val repo = repositoryOver(play)

        val start = testScheduler.currentTime
        val result = repo.connectToBilling().first()

        assertThat((result as BillingConnectionResult.Error).exception)
            .isInstanceOf(BillingException.ServiceUnavailableException::class.java)
        assertThat(play.startConnectionCount).isEqualTo(ConnectionRetryPolicy.DEFAULT_MAX_ATTEMPTS)
        assertThat(testScheduler.currentTime - start).isEqualTo(4 * 30_000L + 2000L + 4000L + 8000L)
    }

    @Test
    fun `with no retry a hung connect reports an error when the setup times out`() = runTest {
        val play = FakePlay().apply { connectCodes.addLast(null) }
        val repo = repositoryOver(play, ConnectionRetryPolicy.None)

        val start = testScheduler.currentTime
        val result = repo.connectToBilling().first()

        assertThat(result).isInstanceOf(BillingConnectionResult.Error::class.java)
        assertThat(testScheduler.currentTime - start).isEqualTo(30_000L)
    }

    @Test
    fun `onBillingServiceDisconnected before setup finishes retries startConnection`() = runTest {
        val play = FakePlay().apply { connectCodes.addLast(null) }
        val repo = repositoryOver(play)
        val op = backgroundScope.async { runCatching { repo.perform(Op.QUERY_PURCHASES) } }
        runCurrent()

        play.stateListeners.single().onBillingServiceDisconnected()
        val outcome = timed { op.await() }

        assertThat(op.await().exceptionOrNull()).isNull()
        assertThat(play.startConnectionCount).isEqualTo(2)
        assertThat(play.clients).hasSize(1)
        assertThat(outcome.elapsedMs).isEqualTo(ConnectionRetryPolicy.DEFAULT_SIMPLE_RETRY_BACKOFF_MILLIS)
    }

    @Test
    fun `with no retry onBillingServiceDisconnected before setup finishes fails the operation`() = runTest {
        val play = FakePlay().apply { connectCodes.addLast(null) }
        val repo = repositoryOver(play, ConnectionRetryPolicy.None)
        val op = backgroundScope.async { runCatching { repo.perform(Op.QUERY_PURCHASES) } }
        runCurrent()

        play.stateListeners.single().onBillingServiceDisconnected()

        assertThat(op.await().exceptionOrNull())
            .isInstanceOf(BillingException.ServiceDisconnectedException::class.java)
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

    private class CapturingLogger : BillingLogger {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        override fun d(message: String, throwable: Throwable?) = Unit
        override fun w(message: String, throwable: Throwable?) {
            warnings += message
        }
        override fun e(message: String, throwable: Throwable?) {
            errors += message
        }
    }
}
