package com.kanetik.billing

import android.app.Activity
import com.android.billingclient.api.BillingFlowParams
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Regression test for `DefaultBillingRepository.launchFlow`'s
 * `CancellationException` contract: a `CancellationException` raised by
 * parent-scope teardown must propagate untouched (not be wrapped into a
 * `BillingException` by the broad `catch (e: Exception)` that handles the
 * other `launchBillingFlow` failures). The launchFlow body has a dedicated
 * `catch (ce: CancellationException) { throw ce }` arm before the broad
 * catch; if it's removed or reordered, structured cancellation is silently
 * broken when the surrounding scope tears down.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultBillingRepositoryLaunchFlowCancellationTest {

    @Test
    fun `launchFlow does not wrap parent-scope CancellationException into BillingException`() = runTest {
        // A connection flow that never emits, so connectToClientAndCall suspends
        // at .first() until either the 30s withTimeout fires (longer than this
        // test runs in virtual time) or the parent scope is cancelled. The latter
        // is what we want to trigger.
        val neverEmittingFlow = MutableSharedFlow<InternalConnectionState>()
        val storage = mockk<BillingClientStorage>(relaxed = true) {
            every { connectionFlow } returns neverEmittingFlow.asSharedFlow()
        }
        val repo = DefaultBillingRepository(
            billingClientStorage = storage,
            logger = BillingLogger.Noop,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            uiDispatcher = UnconfinedTestDispatcher(testScheduler)
        )
        val activity = mockk<Activity>(relaxed = true) {
            every { isFinishing } returns false
            every { isDestroyed } returns false
        }
        val params = mockk<BillingFlowParams>(relaxed = true)

        // Only catch BillingException — if the bug regresses, launchFlow wraps CE
        // and we capture it here. With the fix, CE rethrows past this catch and
        // ends the launch coroutine via cancellation, leaving `wrapped` null.
        var wrapped: BillingException? = null
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                repo.launchFlow(activity, params)
            } catch (e: BillingException) {
                wrapped = e
            }
        }
        // Coroutine has now suspended at connectionFlow.first().
        runCurrent()

        job.cancel()
        job.join()

        assertThat(wrapped).isNull()
    }
}
