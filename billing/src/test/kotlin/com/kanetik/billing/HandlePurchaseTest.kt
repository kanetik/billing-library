package com.kanetik.billing

import android.app.Activity
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class HandlePurchaseTest {

    @Test
    fun `handlePurchase with consume=true on PURCHASED calls consumePurchase(Purchase)`() = runTest {
        val actions = RecordingBillingActions()
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PURCHASED)
        actions.handlePurchase(purchase, consume = true)
        assertThat(actions.consumed).containsExactly(purchase)
        assertThat(actions.acknowledged).isEmpty()
    }

    @Test
    fun `handlePurchase with consume=false on PURCHASED calls acknowledgePurchase(Purchase)`() = runTest {
        val actions = RecordingBillingActions()
        val purchase = fakePurchase(purchaseState = Purchase.PurchaseState.PURCHASED)
        actions.handlePurchase(purchase, consume = false)
        assertThat(actions.consumed).isEmpty()
        assertThat(actions.acknowledged).containsExactly(purchase)
    }

    @Test
    fun `handlePurchase no-ops on PENDING regardless of consume flag`() = runTest {
        val actions = RecordingBillingActions()
        val pending = fakePurchase(purchaseState = Purchase.PurchaseState.PENDING)
        actions.handlePurchase(pending, consume = true)
        actions.handlePurchase(pending, consume = false)
        assertThat(actions.consumed).isEmpty()
        assertThat(actions.acknowledged).isEmpty()
    }

    @Test
    fun `handlePurchase no-ops on UNSPECIFIED_STATE`() = runTest {
        val actions = RecordingBillingActions()
        val unspecified = fakePurchase(purchaseState = Purchase.PurchaseState.UNSPECIFIED_STATE)
        actions.handlePurchase(unspecified, consume = true)
        actions.handlePurchase(unspecified, consume = false)
        assertThat(actions.consumed).isEmpty()
        assertThat(actions.acknowledged).isEmpty()
    }

    /**
     * Minimal BillingActions that records calls into the Purchase-overload methods
     * and exercises the default-impl `handlePurchase` chain.
     */
    private class RecordingBillingActions : BillingActions {
        val consumed = mutableListOf<Purchase>()
        val acknowledged = mutableListOf<Purchase>()

        override suspend fun consumePurchase(purchase: Purchase): String {
            consumed += purchase
            return purchase.purchaseToken
        }

        override suspend fun acknowledgePurchase(purchase: Purchase) {
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
