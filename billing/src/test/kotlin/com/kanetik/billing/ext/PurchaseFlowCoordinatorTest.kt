package com.kanetik.billing.ext

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ProductDetails
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.BillingRepository
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PurchaseFlowCoordinatorTest {

    @Before
    fun setUp() {
        // Coordinator does withContext(Dispatchers.Main) for launchFlow; redirect to
        // a TestDispatcher so it advances under TestScope's virtual time.
        Dispatchers.setMain(StandardTestDispatcher())
        // Mock the toOneTimeFlowParams extension so we don't run PBL's
        // BillingFlowParams.Builder validation (which doesn't work in pure-JVM tests).
        mockkStatic("com.kanetik.billing.ext.FlowParamsExtensionsKt")
        every {
            any<ProductDetails>().toOneTimeFlowParams(any(), any())
        } returns mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkStatic("com.kanetik.billing.ext.FlowParamsExtensionsKt")
    }

    @Test
    fun `successful launch returns Success`() = runTest {
        val billing = mockk<BillingRepository>()
        coEvery { billing.launchFlow(any(), any()) } returns Unit

        val coordinator = PurchaseFlowCoordinator(
            billingRepository = billing,
            scope = backgroundScope,
            logger = BillingLogger.Noop
        )

        val result = coordinator.launch(activityResumed(), productDetails())
        assertThat(result).isEqualTo(PurchaseFlowResult.Success)
        coVerify(exactly = 1) { billing.launchFlow(any(), any()) }
    }

    @Test
    fun `launch on finishing activity returns InvalidActivityState`() = runTest {
        val billing = mockk<BillingRepository>(relaxed = true)
        val coordinator = PurchaseFlowCoordinator(
            billingRepository = billing,
            scope = backgroundScope,
            logger = BillingLogger.Noop
        )
        val activity = mockk<Activity>(relaxed = true).also {
            every { it.isFinishing } returns true
        }
        val result = coordinator.launch(activity, productDetails())
        assertThat(result).isEqualTo(PurchaseFlowResult.InvalidActivityState)
        // Billing flow should not have been launched.
        coVerify(exactly = 0) { billing.launchFlow(any(), any()) }
    }

    @Test
    fun `second launch while flag is set returns AlreadyInProgress`() = runTest {
        val billing = mockk<BillingRepository>()
        coEvery { billing.launchFlow(any(), any()) } returns Unit

        val coordinator = PurchaseFlowCoordinator(
            billingRepository = billing,
            scope = backgroundScope,
            logger = BillingLogger.Noop
        )

        // First launch succeeds; the in-flight flag stays set (caller hasn't
        // called markComplete yet).
        val first = coordinator.launch(activityResumed(), productDetails())
        assertThat(first).isEqualTo(PurchaseFlowResult.Success)

        // Second launch with flag still set → AlreadyInProgress, no PBL call.
        val second = coordinator.launch(activityResumed(), productDetails())
        assertThat(second).isEqualTo(PurchaseFlowResult.AlreadyInProgress)
        coVerify(exactly = 1) { billing.launchFlow(any(), any()) }
    }

    @Test
    fun `BillingUnavailableException maps to BillingUnavailable result and clears in-flight flag`() = runTest {
        val billing = mockk<BillingRepository>()
        val unavailableException = BillingException.BillingUnavailableException(
            BillingResult.newBuilder()
                .setResponseCode(com.android.billingclient.api.BillingClient.BillingResponseCode.BILLING_UNAVAILABLE)
                .build()
        )
        coEvery { billing.launchFlow(any(), any()) } throws unavailableException

        val coordinator = PurchaseFlowCoordinator(
            billingRepository = billing,
            scope = backgroundScope,
            logger = BillingLogger.Noop
        )

        val result = coordinator.launch(activityResumed(), productDetails())
        assertThat(result).isEqualTo(PurchaseFlowResult.BillingUnavailable)

        // Flag should be cleared — a follow-up launch shouldn't be blocked.
        coEvery { billing.launchFlow(any(), any()) } returns Unit
        val second = coordinator.launch(activityResumed(), productDetails())
        assertThat(second).isEqualTo(PurchaseFlowResult.Success)
    }

    @Test
    fun `arbitrary throwable maps to Error result preserving the cause type and message`() = runTest {
        val billing = mockk<BillingRepository>()
        coEvery { billing.launchFlow(any(), any()) } throws IllegalStateException("simulated")

        val coordinator = PurchaseFlowCoordinator(
            billingRepository = billing,
            scope = backgroundScope,
            logger = BillingLogger.Noop
        )

        val result = coordinator.launch(activityResumed(), productDetails())
        assertThat(result).isInstanceOf(PurchaseFlowResult.Error::class.java)
        // Coroutines may rewrap cross-context exceptions, so we don't assert
        // identity — just type + message preservation.
        val cause = (result as PurchaseFlowResult.Error).cause
        assertThat(cause).isInstanceOf(IllegalStateException::class.java)
        assertThat(cause.message).isEqualTo("simulated")
    }

    @Test
    fun `CancellationException is rethrown not swallowed`() = runTest {
        val billing = mockk<BillingRepository>()
        coEvery { billing.launchFlow(any(), any()) } throws CancellationException("test")

        val coordinator = PurchaseFlowCoordinator(
            billingRepository = billing,
            scope = backgroundScope,
            logger = BillingLogger.Noop
        )

        var thrown: Throwable? = null
        try {
            coordinator.launch(activityResumed(), productDetails())
        } catch (e: CancellationException) {
            thrown = e
        }
        assertThat(thrown).isNotNull()
    }

    @Test
    fun `markComplete clears the in-flight flag and unblocks subsequent launches`() = runTest {
        val billing = mockk<BillingRepository>()
        coEvery { billing.launchFlow(any(), any()) } returns Unit

        val coordinator = PurchaseFlowCoordinator(
            billingRepository = billing,
            scope = backgroundScope,
            logger = BillingLogger.Noop
        )

        // No-op when nothing's in flight — must not throw.
        coordinator.markComplete()

        // First launch sets the flag.
        val first = coordinator.launch(activityResumed(), productDetails())
        assertThat(first).isEqualTo(PurchaseFlowResult.Success)
        // Without markComplete, a follow-up launch sees the flag set.
        assertThat(coordinator.launch(activityResumed(), productDetails()))
            .isEqualTo(PurchaseFlowResult.AlreadyInProgress)

        // markComplete clears the flag → next launch goes through.
        coordinator.markComplete()
        assertThat(coordinator.launch(activityResumed(), productDetails()))
            .isEqualTo(PurchaseFlowResult.Success)
    }

    @Test
    fun `watchdog clears the flag after the configured timeout`() = runTest {
        val billing = mockk<BillingRepository>()
        coEvery { billing.launchFlow(any(), any()) } returns Unit

        val coordinator = PurchaseFlowCoordinator(
            billingRepository = billing,
            scope = backgroundScope,
            logger = BillingLogger.Noop,
            watchdogTimeoutMs = 60_000L
        )

        // Successful launch sets the in-flight flag (cleared only by markComplete or watchdog).
        val first = coordinator.launch(activityResumed(), productDetails())
        assertThat(first).isEqualTo(PurchaseFlowResult.Success)
        // Without markComplete, a follow-up launch sees the flag set.
        val before = coordinator.launch(activityResumed(), productDetails())
        assertThat(before).isEqualTo(PurchaseFlowResult.AlreadyInProgress)

        // Advance virtual time past the watchdog timeout — flag should clear.
        advanceTimeBy(61_000L)
        advanceUntilIdle()

        // Now a new launch should go through.
        val after = coordinator.launch(activityResumed(), productDetails())
        assertThat(after).isEqualTo(PurchaseFlowResult.Success)
    }

    private fun activityResumed(): Activity {
        val lifecycle = mockk<Lifecycle>(relaxed = true).also {
            every { it.currentState } returns Lifecycle.State.RESUMED
        }
        return mockk<ActivityLifecycle>(relaxed = true).also {
            every { it.isFinishing } returns false
            every { it.isDestroyed } returns false
            every { it.lifecycle } returns lifecycle
        }
    }

    private fun productDetails(): ProductDetails = mockk<ProductDetails>(relaxed = true).also {
        every { it.oneTimePurchaseOfferDetailsList } returns null
    }

    abstract class ActivityLifecycle : Activity(), LifecycleOwner
}
