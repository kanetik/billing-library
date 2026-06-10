package com.kanetik.billing.factory

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PurchasesUpdatedListener
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.ConnectionRetryPolicy
import com.kanetik.billing.InternalConnectionState
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests the internal connection-retry behavior of
 * [CoroutinesBillingConnectionFactory]: transient `startConnection` failures
 * (those classified [com.kanetik.billing.RetryType.SIMPLE_RETRY] /
 * [com.kanetik.billing.RetryType.EXPONENTIAL_RETRY]) are retried per the
 * [ConnectionRetryPolicy] before a terminal [InternalConnectionState.Failed]
 * is surfaced; non-transient failures surface immediately.
 *
 * The [BillingClient] is mocked; its `startConnection` synchronously drives the
 * captured [BillingClientStateListener] from a queue of canned
 * [BillingResult]s, one per attempt. Backoff `delay`s run on the
 * `runTest` virtual clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CoroutinesBillingConnectionFactoryTest {

    @Test
    fun `retries SIMPLE_RETRY failure then emits Connected`() = runTest {
        val attempts = AtomicInteger()
        val factory = factoryFor(
            setupResults = listOf(
                result(BillingResponseCode.SERVICE_DISCONNECTED),
                result(BillingResponseCode.OK)
            ),
            policy = ConnectionRetryPolicy(),
            startConnectionCount = attempts
        )

        val state = factory.createBillingConnectionFlow(noopListener()).first()

        assertThat(state).isInstanceOf(InternalConnectionState.Connected::class.java)
        assertThat(attempts.get()).isEqualTo(2)
    }

    @Test
    fun `retries EXPONENTIAL_RETRY failure then emits Connected`() = runTest {
        val attempts = AtomicInteger()
        val factory = factoryFor(
            setupResults = listOf(
                result(BillingResponseCode.SERVICE_UNAVAILABLE),
                result(BillingResponseCode.OK)
            ),
            policy = ConnectionRetryPolicy(),
            startConnectionCount = attempts
        )

        val state = factory.createBillingConnectionFlow(noopListener()).first()

        assertThat(state).isInstanceOf(InternalConnectionState.Connected::class.java)
        assertThat(attempts.get()).isEqualTo(2)
    }

    @Test
    fun `surfaces Failed once SIMPLE_RETRY budget is exhausted`() = runTest {
        val attempts = AtomicInteger()
        val factory = factoryFor(
            setupResults = List(3) { result(BillingResponseCode.SERVICE_DISCONNECTED) },
            policy = ConnectionRetryPolicy(maxAttempts = 3),
            startConnectionCount = attempts
        )

        val state = factory.createBillingConnectionFlow(noopListener()).first()

        assertThat(state).isInstanceOf(InternalConnectionState.Failed::class.java)
        assertThat((state as InternalConnectionState.Failed).exception)
            .isInstanceOf(BillingException.ServiceDisconnectedException::class.java)
        assertThat(attempts.get()).isEqualTo(3)
    }

    @Test
    fun `non-transient failure surfaces immediately without retry`() = runTest {
        val attempts = AtomicInteger()
        val factory = factoryFor(
            setupResults = listOf(result(BillingResponseCode.BILLING_UNAVAILABLE)),
            policy = ConnectionRetryPolicy(),
            startConnectionCount = attempts
        )

        val state = factory.createBillingConnectionFlow(noopListener()).first()

        assertThat(state).isInstanceOf(InternalConnectionState.Failed::class.java)
        assertThat((state as InternalConnectionState.Failed).exception)
            .isInstanceOf(BillingException.BillingUnavailableException::class.java)
        assertThat(attempts.get()).isEqualTo(1)
    }

    @Test
    fun `None policy surfaces the first transient failure immediately`() = runTest {
        val attempts = AtomicInteger()
        val factory = factoryFor(
            // A successful second result is queued but must never be consumed —
            // ConnectionRetryPolicy.None disables retry.
            setupResults = listOf(
                result(BillingResponseCode.SERVICE_DISCONNECTED),
                result(BillingResponseCode.OK)
            ),
            policy = ConnectionRetryPolicy.None,
            startConnectionCount = attempts
        )

        val state = factory.createBillingConnectionFlow(noopListener()).first()

        assertThat(state).isInstanceOf(InternalConnectionState.Failed::class.java)
        assertThat((state as InternalConnectionState.Failed).exception)
            .isInstanceOf(BillingException.ServiceDisconnectedException::class.java)
        assertThat(attempts.get()).isEqualTo(1)
    }

    @Test
    fun `applies fixed backoff between SIMPLE_RETRY attempts`() = runTest {
        val factory = factoryFor(
            setupResults = List(3) { result(BillingResponseCode.SERVICE_DISCONNECTED) },
            policy = ConnectionRetryPolicy(maxAttempts = 3, simpleRetryBackoffMillis = 500L),
            startConnectionCount = AtomicInteger()
        )

        val start = currentTime
        factory.createBillingConnectionFlow(noopListener()).first()

        // Two backoffs (attempt 1→2 and 2→3), 500ms each.
        assertThat(currentTime - start).isEqualTo(1000L)
    }

    @Test
    fun `grows exponential backoff between EXPONENTIAL_RETRY attempts`() = runTest {
        val factory = factoryFor(
            setupResults = List(3) { result(BillingResponseCode.SERVICE_UNAVAILABLE) },
            policy = ConnectionRetryPolicy(
                maxAttempts = 3,
                exponentialBaseBackoffMillis = 2000L,
                exponentialBackoffFactor = 2
            ),
            startConnectionCount = AtomicInteger()
        )

        val start = currentTime
        factory.createBillingConnectionFlow(noopListener()).first()

        // Backoff after attempt 1 = 2000ms, after attempt 2 = 4000ms → 6000ms total.
        assertThat(currentTime - start).isEqualTo(6000L)
    }

    @Test
    fun `ends the billing connection when the collector cancels after Connected`() = runTest {
        val client = mockk<BillingClient>(relaxed = true)
        every { client.isReady } returns true
        every { client.startConnection(any()) } answers {
            firstArg<BillingClientStateListener>().onBillingSetupFinished(result(BillingResponseCode.OK))
        }
        val factory = CoroutinesBillingConnectionFactory(
            context = mockk(relaxed = true),
            billingClientFactory = clientFactoryReturning(client),
            retryPolicy = ConnectionRetryPolicy(),
            logger = BillingLogger.Noop
        )

        // first() collects a single emission then cancels the upstream, which
        // tears down the callbackFlow producer and runs the cleanup finally.
        factory.createBillingConnectionFlow(noopListener()).first()
        // Let the producer's structured-cancellation unwind (and its finally)
        // run to completion on the virtual clock before asserting.
        testScheduler.advanceUntilIdle()

        verify { client.endConnection() }
    }

    // --- helpers ---

    private fun factoryFor(
        setupResults: List<BillingResult>,
        policy: ConnectionRetryPolicy,
        startConnectionCount: AtomicInteger
    ): CoroutinesBillingConnectionFactory {
        val queued = ArrayDeque(setupResults)
        val client = mockk<BillingClient>(relaxed = true)
        every { client.startConnection(any()) } answers {
            startConnectionCount.incrementAndGet()
            firstArg<BillingClientStateListener>().onBillingSetupFinished(queued.removeFirst())
        }
        return CoroutinesBillingConnectionFactory(
            context = mockk(relaxed = true),
            billingClientFactory = clientFactoryReturning(client),
            retryPolicy = policy,
            logger = BillingLogger.Noop
        )
    }

    private fun clientFactoryReturning(client: BillingClient): BillingClientFactory =
        object : BillingClientFactory {
            override fun createBillingClient(
                context: Context,
                listener: PurchasesUpdatedListener
            ): BillingClient = client
        }

    private fun noopListener(): PurchasesUpdatedListener =
        PurchasesUpdatedListener { _, _ -> }

    private fun result(responseCode: Int): BillingResult =
        BillingResult.newBuilder().setResponseCode(responseCode).build()
}
