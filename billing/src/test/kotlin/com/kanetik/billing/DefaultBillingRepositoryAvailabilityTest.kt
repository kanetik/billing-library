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
        connection: MutableSharedFlow<BillingConnectionResult>,
        playStoreUsable: Boolean,
        scheduler: kotlinx.coroutines.test.TestCoroutineScheduler
    ): DefaultBillingRepository {
        val storage = mockk<BillingClientStorage>(relaxed = true) {
            every { connectionResultFlow } returns connection.asSharedFlow()
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
        val connection = MutableSharedFlow<BillingConnectionResult>()
        val r = repo(connection, playStoreUsable = false, scheduler = testScheduler)

        assertThat(r.queryBillingAvailability()).isEqualTo(BillingAvailability.UNAVAILABLE)
    }

    @Test
    fun `Play Store present and connection success returns AVAILABLE`() = runTest {
        val connection = MutableSharedFlow<BillingConnectionResult>(replay = 1).apply {
            tryEmit(BillingConnectionResult.Success)
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
        val connection = MutableSharedFlow<BillingConnectionResult>(replay = 1).apply {
            tryEmit(BillingConnectionResult.Error(BillingException.fromResult(code3)))
        }
        val r = repo(connection, playStoreUsable = true, scheduler = testScheduler)

        assertThat(r.queryBillingAvailability()).isEqualTo(BillingAvailability.UNKNOWN)
    }

    @Test
    fun `Play Store present but connection hangs returns UNKNOWN via timeout`() = runTest {
        // Never emits; withTimeout fires in virtual time -> UNKNOWN.
        val connection = MutableSharedFlow<BillingConnectionResult>()
        val r = repo(connection, playStoreUsable = true, scheduler = testScheduler)

        assertThat(r.queryBillingAvailability()).isEqualTo(BillingAvailability.UNKNOWN)
    }
}
