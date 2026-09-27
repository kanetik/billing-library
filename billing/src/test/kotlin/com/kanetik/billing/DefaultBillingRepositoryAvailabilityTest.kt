package com.kanetik.billing

import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Verifies [DefaultBillingRepository.queryBillingAvailability]'s
 * terminal-vs-transient contract:
 *  - Play Store package absent/disabled is the only path to the terminal
 *    [BillingAvailability.UNAVAILABLE] verdict (no connection attempted).
 *  - With the Play Store present, a successful connection is [AVAILABLE]; ANY
 *    connection failure (including a transient `BILLING_UNAVAILABLE`) or a
 *    timeout is [UNKNOWN], never UNAVAILABLE — so a consumer can't be tricked
 *    into a terminal decision by a transient first-launch hiccup.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultBillingRepositoryAvailabilityTest {

    private fun repo(
        connection: MutableSharedFlow<InternalConnectionState>,
        playStoreUsable: Boolean,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler,
        clientLive: Boolean = true
    ): DefaultBillingRepository {
        val storage = mockk<BillingClientStorage>(relaxed = true) {
            every { connectionFlow } returns connection.asSharedFlow()
            every { isLive(any()) } returns clientLive
        }
        return DefaultBillingRepository(
            billingClientStorage = storage,
            logger = BillingLogger.Noop,
            ioDispatcher = UnconfinedTestDispatcher(scheduler),
            uiDispatcher = UnconfinedTestDispatcher(scheduler),
            playStoreEnvironment = PlayStoreEnvironment { playStoreUsable }
        )
    }

    @Test
    fun `Play Store absent or disabled returns UNAVAILABLE without connecting`() = runTest {
        // A connection flow that would never emit — if the code tried to connect,
        // it'd hang until timeout. It must short-circuit before that.
        val connection = MutableSharedFlow<InternalConnectionState>()
        val r = repo(connection, playStoreUsable = false, scheduler = testScheduler)

        assertThat(r.queryBillingAvailability()).isEqualTo(BillingAvailability.UNAVAILABLE)
    }

    @Test
    fun `Play Store present and connection success returns AVAILABLE`() = runTest {
        val client = mockk<BillingClient> { every { isReady } returns true }
        val connection = MutableSharedFlow<InternalConnectionState>(replay = 1).apply {
            tryEmit(InternalConnectionState.Connected(client))
        }
        val r = repo(connection, playStoreUsable = true, scheduler = testScheduler)

        assertThat(r.queryBillingAvailability()).isEqualTo(BillingAvailability.AVAILABLE)
    }

    @Test
    fun `Play Store present but transient BILLING_UNAVAILABLE returns UNKNOWN not UNAVAILABLE`() = runTest {
        val code3 = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
            .setDebugMessage("Billing service unavailable on device.")
            .build()
        val connection = MutableSharedFlow<InternalConnectionState>(replay = 1)
        val r = repo(connection, playStoreUsable = true, scheduler = testScheduler)

        val availability = backgroundScope.async { r.queryBillingAvailability() }
        runCurrent()
        connection.emit(InternalConnectionState.Failed(BillingException.fromResult(code3)))

        assertThat(availability.await()).isEqualTo(BillingAvailability.UNKNOWN)
        assertThat(testScheduler.currentTime).isEqualTo(0L)
    }

    @Test
    fun `Play Store present but connection hangs returns UNKNOWN via timeout`() = runTest {
        // Never emits; withTimeout fires in virtual time -> UNKNOWN.
        val connection = MutableSharedFlow<InternalConnectionState>()
        val r = repo(connection, playStoreUsable = true, scheduler = testScheduler)

        assertThat(r.queryBillingAvailability()).isEqualTo(BillingAvailability.UNKNOWN)
    }

    @Test
    fun `a live connection returns AVAILABLE without reconnecting`() = runTest {
        val play = FakePlay()
        val r = repositoryOver(play)
        backgroundScope.launch { r.connectToBilling().collect { } }
        runCurrent()

        assertThat(r.queryBillingAvailability()).isEqualTo(BillingAvailability.AVAILABLE)
        assertThat(play.startConnectionCount).isEqualTo(1)
    }

    @Test
    fun `a Success from before the idle stop does not yield AVAILABLE`() = runTest {
        val play = FakePlay()
        val r = repositoryOver(play)
        r.connectToBilling().first()
        advanceTimeBy(120_001)
        runCurrent()
        play.connectCodes.addLast(BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)

        assertThat(r.queryBillingAvailability()).isEqualTo(BillingAvailability.UNKNOWN)
        runCurrent()
        assertThat(play.startConnectionCount).isEqualTo(2)
    }

    @Test
    fun `a replayed connection whose client is no longer live does not yield AVAILABLE`() = runTest {
        val client = mockk<BillingClient> { every { isReady } returns true }
        val connection = MutableSharedFlow<InternalConnectionState>(replay = 1).apply {
            tryEmit(InternalConnectionState.Connected(client))
        }
        val r = repo(connection, playStoreUsable = true, scheduler = testScheduler, clientLive = false)

        assertThat(r.queryBillingAvailability()).isEqualTo(BillingAvailability.UNKNOWN)
    }
}
