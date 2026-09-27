@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kanetik.billing

import android.app.Activity
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingFlowParams
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultBillingRepositoryLaunchFlowEchoSuppressionTest {

    private fun activity(): Activity = mockk(relaxed = true) {
        every { isFinishing } returns false
        every { isDestroyed } returns false
    }

    private fun params(): BillingFlowParams = mockk(relaxed = true)

    @Test
    fun `a synchronous failure echoed with the same code inside the window yields only the thrown exception`() = runTest {
        var now = 0L
        val play = FakePlay().apply { script(Op.LAUNCH_FLOW, BillingResponseCode.BILLING_UNAVAILABLE) }
        val repo = repositoryOver(play, clock = { now })
        val events = mutableListOf<PurchaseEvent>()
        backgroundScope.launch { repo.observePurchaseUpdates().collect { events += it } }
        runCurrent()

        val thrown = runCatching { repo.launchFlow(activity(), params()) }.exceptionOrNull()
        now += 12
        play.purchasesUpdatedListeners.single().onPurchasesUpdated(
            billingResult(BillingResponseCode.BILLING_UNAVAILABLE),
            null
        )
        runCurrent()

        assertThat(thrown).isInstanceOf(BillingException.BillingUnavailableException::class.java)
        assertThat(events).isEmpty()
    }

    @Test
    fun `a genuine flow-time failure with no prior synchronous failure still emits UserBillingError`() = runTest {
        val play = FakePlay().apply { script(Op.LAUNCH_FLOW, BillingResponseCode.OK) }
        val repo = repositoryOver(play)
        val events = mutableListOf<PurchaseEvent>()
        backgroundScope.launch { repo.observePurchaseUpdates().collect { events += it } }
        runCurrent()

        repo.launchFlow(activity(), params())
        play.purchasesUpdatedListeners.single().onPurchasesUpdated(
            billingResult(BillingResponseCode.BILLING_UNAVAILABLE),
            null
        )
        runCurrent()

        assertThat(events.single()).isInstanceOf(FlowOutcome.UserBillingError::class.java)
    }

    @Test
    fun `an echo arriving after the 500ms window is emitted normally`() = runTest {
        var now = 0L
        val play = FakePlay().apply { script(Op.LAUNCH_FLOW, BillingResponseCode.BILLING_UNAVAILABLE) }
        val repo = repositoryOver(play, clock = { now })
        val events = mutableListOf<PurchaseEvent>()
        backgroundScope.launch { repo.observePurchaseUpdates().collect { events += it } }
        runCurrent()

        runCatching { repo.launchFlow(activity(), params()) }
        now += 501
        play.purchasesUpdatedListeners.single().onPurchasesUpdated(
            billingResult(BillingResponseCode.BILLING_UNAVAILABLE),
            null
        )
        runCurrent()

        assertThat(events.single()).isInstanceOf(FlowOutcome.UserBillingError::class.java)
    }

    @Test
    fun `a different response code inside the window is emitted normally`() = runTest {
        var now = 0L
        val play = FakePlay().apply { script(Op.LAUNCH_FLOW, BillingResponseCode.BILLING_UNAVAILABLE) }
        val repo = repositoryOver(play, clock = { now })
        val events = mutableListOf<PurchaseEvent>()
        backgroundScope.launch { repo.observePurchaseUpdates().collect { events += it } }
        runCurrent()

        runCatching { repo.launchFlow(activity(), params()) }
        now += 12
        play.purchasesUpdatedListeners.single().onPurchasesUpdated(
            billingResult(BillingResponseCode.ITEM_ALREADY_OWNED),
            null
        )
        runCurrent()

        assertThat(events.single()).isInstanceOf(FlowOutcome.ItemAlreadyOwned::class.java)
    }

    @Test
    fun `starting a new launchFlow cancels the pending suppression so the first attempt's echo is not swallowed`() = runTest {
        var now = 0L
        val play = FakePlay().apply {
            script(Op.LAUNCH_FLOW, BillingResponseCode.BILLING_UNAVAILABLE, BillingResponseCode.OK)
        }
        val repo = repositoryOver(play, clock = { now })
        val events = mutableListOf<PurchaseEvent>()
        backgroundScope.launch { repo.observePurchaseUpdates().collect { events += it } }
        runCurrent()

        // First attempt fails synchronously and arms suppression for code 3.
        runCatching { repo.launchFlow(activity(), params()) }
        // A fast retry launches before the first attempt's echo arrives.
        repo.launchFlow(activity(), params())
        // The first attempt's echo now arrives - the retry above must have
        // cancelled the arm it left behind.
        now += 12
        play.purchasesUpdatedListeners.single().onPurchasesUpdated(
            billingResult(BillingResponseCode.BILLING_UNAVAILABLE),
            null
        )
        runCurrent()

        assertThat(events.single()).isInstanceOf(FlowOutcome.UserBillingError::class.java)
    }

    @Test
    fun `a synchronous ITEM_ALREADY_OWNED echoed within the window is also suppressed`() = runTest {
        var now = 0L
        val play = FakePlay().apply { script(Op.LAUNCH_FLOW, BillingResponseCode.ITEM_ALREADY_OWNED) }
        val repo = repositoryOver(play, clock = { now })
        val events = mutableListOf<PurchaseEvent>()
        backgroundScope.launch { repo.observePurchaseUpdates().collect { events += it } }
        runCurrent()

        val thrown = runCatching { repo.launchFlow(activity(), params()) }.exceptionOrNull()
        now += 12
        play.purchasesUpdatedListeners.single().onPurchasesUpdated(
            billingResult(BillingResponseCode.ITEM_ALREADY_OWNED),
            null
        )
        runCurrent()

        assertThat(thrown).isInstanceOf(BillingException.ItemAlreadyOwnedException::class.java)
        assertThat(events).isEmpty()
    }
}
