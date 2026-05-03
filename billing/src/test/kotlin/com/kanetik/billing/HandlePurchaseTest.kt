package com.kanetik.billing

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HandlePurchaseTest {

    @Test
    fun `handlePurchase with consume=true on PURCHASED calls consumePurchase(Purchase) and returns Success`() = runTest {
        val actions = RecordingBillingActions()
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PURCHASED)
        val result = actions.handlePurchase(purchase, consume = true)
        assertThat(actions.consumed).containsExactly(purchase)
        assertThat(actions.acknowledged).isEmpty()
        assertThat(result).isEqualTo(HandlePurchaseResult.Success)
    }

    @Test
    fun `handlePurchase with consume=false on PURCHASED calls acknowledgePurchase(Purchase) and returns Success`() = runTest {
        val actions = RecordingBillingActions()
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PURCHASED)
        val result = actions.handlePurchase(purchase, consume = false)
        assertThat(actions.consumed).isEmpty()
        assertThat(actions.acknowledged).containsExactly(purchase)
        assertThat(result).isEqualTo(HandlePurchaseResult.Success)
    }

    @Test
    fun `handlePurchase returns NotPurchased on PENDING regardless of consume flag`() = runTest {
        val actions = RecordingBillingActions()
        val pending = fakePurchase(purchaseState = Purchase.PurchaseState.PENDING)
        assertThat(actions.handlePurchase(pending, consume = true)).isEqualTo(HandlePurchaseResult.NotPurchased)
        assertThat(actions.handlePurchase(pending, consume = false)).isEqualTo(HandlePurchaseResult.NotPurchased)
        assertThat(actions.consumed).isEmpty()
        assertThat(actions.acknowledged).isEmpty()
    }

    @Test
    fun `handlePurchase returns NotPurchased on UNSPECIFIED_STATE`() = runTest {
        val actions = RecordingBillingActions()
        val unspecified = fakePurchase(purchaseState = Purchase.PurchaseState.UNSPECIFIED_STATE)
        assertThat(actions.handlePurchase(unspecified, consume = true)).isEqualTo(HandlePurchaseResult.NotPurchased)
        assertThat(actions.handlePurchase(unspecified, consume = false)).isEqualTo(HandlePurchaseResult.NotPurchased)
        assertThat(actions.consumed).isEmpty()
        assertThat(actions.acknowledged).isEmpty()
    }

    @Test
    fun `handlePurchase returns Failure carrying the BillingException when consume throws`() = runTest {
        val thrown = BillingException.NetworkErrorException(
            BillingResult.newBuilder().setResponseCode(2).build()
        )
        val actions = RecordingBillingActions(consumeThrows = thrown)
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PURCHASED)

        val result = actions.handlePurchase(purchase, consume = true)

        assertThat(result).isInstanceOf(HandlePurchaseResult.Failure::class.java)
        assertThat((result as HandlePurchaseResult.Failure).exception).isSameInstanceAs(thrown)
    }

    @Test
    fun `handlePurchase returns Failure carrying the BillingException when acknowledge throws`() = runTest {
        val thrown = BillingException.ServiceDisconnectedException(
            BillingResult.newBuilder().setResponseCode(-1).build()
        )
        val actions = RecordingBillingActions(acknowledgeThrows = thrown)
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PURCHASED)

        val result = actions.handlePurchase(purchase, consume = false)

        assertThat(result).isInstanceOf(HandlePurchaseResult.Failure::class.java)
        assertThat((result as HandlePurchaseResult.Failure).exception).isSameInstanceAs(thrown)
    }

    @Test
    fun `handlePurchase wraps non-BillingException throwables as Failure(UnknownException)`() = runTest {
        // A custom BillingActions implementation might throw something other than
        // BillingException — a NullPointerException from a `!!` contract check,
        // an IllegalStateException from a fake, etc. The typed-result contract
        // requires those to be wrapped, not escape.
        val thrown = IllegalStateException("simulated non-billing failure")
        val actions = RecordingBillingActions(consumeThrowsRaw = thrown)
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PURCHASED)

        val result = actions.handlePurchase(purchase, consume = true)

        assertThat(result).isInstanceOf(HandlePurchaseResult.Failure::class.java)
        val failure = result as HandlePurchaseResult.Failure
        assertThat(failure.exception).isInstanceOf(BillingException.UnknownException::class.java)
        // Original exception type and message should appear in the wrapped
        // BillingResult.debugMessage so the failure isn't a black box.
        assertThat(failure.exception.result?.debugMessage).contains("IllegalStateException")
        assertThat(failure.exception.result?.debugMessage).contains("simulated non-billing failure")
    }

    @Test
    fun `handlePurchase rethrows CancellationException without wrapping`() = runTest {
        // Structured cancellation must propagate. Wrapping CancellationException
        // into a Failure would silently swallow scope cancellation (e.g. ViewModel
        // cleared mid-purchase).
        val thrown = kotlinx.coroutines.CancellationException("cancelled")
        val actions = RecordingBillingActions(consumeThrowsRaw = thrown)
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PURCHASED)

        var caught: Throwable? = null
        try {
            actions.handlePurchase(purchase, consume = true)
        } catch (e: kotlinx.coroutines.CancellationException) {
            caught = e
        }
        assertThat(caught).isSameInstanceAs(thrown)
    }

    /**
     * Minimal BillingActions that records calls into the Purchase-overload methods
     * and exercises the default-impl `handlePurchase` chain. Optionally configured
     * to throw on consume / acknowledge to exercise the Failure branch.
     */
    private class RecordingBillingActions(
        private val consumeThrows: BillingException? = null,
        private val acknowledgeThrows: BillingException? = null,
        private val consumeThrowsRaw: Throwable? = null
    ) : BillingActions {
        val consumed = mutableListOf<Purchase>()
        val acknowledged = mutableListOf<Purchase>()

        override suspend fun consumePurchase(purchase: Purchase): String {
            consumeThrowsRaw?.let { throw it }
            consumeThrows?.let { throw it }
            consumed += purchase
            return purchase.purchaseToken
        }

        override suspend fun acknowledgePurchase(purchase: Purchase) {
            acknowledgeThrows?.let { throw it }
            acknowledged += purchase
        }

        // --- stubs for the rest of the interface (not exercised in these tests) ---
        override suspend fun isFeatureSupported(feature: String): Boolean = true
        override suspend fun queryPurchases(params: QueryPurchasesParams): List<Purchase> = emptyList()
        override suspend fun queryProductDetails(params: QueryProductDetailsParams): List<ProductDetails> = emptyList()
        override suspend fun queryProductDetailsWithUnfetched(params: QueryProductDetailsParams): ProductDetailsQuery =
            ProductDetailsQuery(emptyList(), emptyList())
        override suspend fun consumePurchase(params: ConsumeParams): String = "test-token"
        override suspend fun acknowledgePurchase(params: AcknowledgePurchaseParams) = Unit
        override suspend fun launchFlow(activity: Activity, params: BillingFlowParams) = Unit
        override suspend fun showInAppMessages(
            activity: Activity,
            params: InAppMessageParams
        ): BillingInAppMessageResult = BillingInAppMessageResult.NoActionNeeded
    }
}
