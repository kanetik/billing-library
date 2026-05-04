package com.kanetik.billing.sample

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.kanetik.billing.BillingConnectionResult
import com.kanetik.billing.BillingRepository
import com.kanetik.billing.BillingRepositoryCreator
import com.kanetik.billing.FlowOutcome
import com.kanetik.billing.HandlePurchaseResult
import com.kanetik.billing.OwnedPurchases
import com.kanetik.billing.PurchaseEvent
import com.kanetik.billing.PurchaseRevoked
import com.kanetik.billing.RevocationReason
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.ext.toOneTimeFlowParams
import com.kanetik.billing.lifecycle.BillingConnectionLifecycleManager
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SampleViewModel(application: Application) : AndroidViewModel(application) {

    val billing: BillingRepository = BillingRepositoryCreator.create(
        context = application,
        logger = BillingLogger.Android,
    )

    val lifecycleManager = BillingConnectionLifecycleManager(billing)

    private val _state = MutableStateFlow(SampleUiState())
    val state: StateFlow<SampleUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            billing.connectToBilling().collect { result ->
                _state.update { it.copy(connection = result) }
                if (result is BillingConnectionResult.Success) {
                    appendLog("connected to billing")
                }
            }
        }
        viewModelScope.launch {
            billing.observePurchaseUpdates().collect { event ->
                _state.update { it.copy(lastEvent = event) }
                appendLog("purchase event: ${event::class.simpleName}")
                when (event) {
                    is OwnedPurchases.Live -> event.purchases.forEach { handlePurchaseAndLog(it) }
                    is OwnedPurchases.Recovered -> event.purchases.forEach { handlePurchaseAndLog(it) }
                    is FlowOutcome -> {
                        // Pending / Canceled / ItemAlreadyOwned / ItemUnavailable /
                        // UnknownResponse — sample just logs the variant name above.
                        // Real apps should branch per sub-variant. Critically: do NOT
                        // write event.purchases to an entitlement cache from this branch
                        // — see PurchaseEvent KDoc. The library tracks acknowledged
                        // tokens internally now (#6), so consumer-side dedupe of
                        // Recovered is no longer required.
                    }
                    is PurchaseRevoked -> {
                        // Real apps revoke entitlement here (clear premium flag, kick
                        // back to a paywall, etc.). The sample just logs — the goal of
                        // showing this branch is to demonstrate that revocation events
                        // arrive on the same flow as OwnedPurchases / FlowOutcome, so
                        // consumers don't need to maintain a parallel pipeline.
                        appendLog("revoked: ${event.purchaseToken} (${event.reason})")
                    }
                }
            }
        }
    }

    /**
     * For `android.test.purchased`, consume = true so a fresh run can re-purchase.
     * For non-consumable IAP in real apps, pass consume = false to acknowledge-only.
     * Real apps would also grant entitlement on HandlePurchaseResult.Success — this
     * sample just logs the outcome.
     *
     * @return true iff the result is safe to treat as handled / granted —
     *   either Success (acknowledge/consume call landed) or AlreadyAcknowledged
     *   (no PBL call needed because the purchase was already acknowledged
     *   server-side; entitlement-equivalent for the consumer).
     */
    private suspend fun handlePurchaseAndLog(purchase: Purchase): Boolean {
        return when (val outcome = billing.handlePurchase(purchase, consume = true)) {
            HandlePurchaseResult.Success -> {
                appendLog("handlePurchase OK for ${purchase.products}")
                true
            }
            HandlePurchaseResult.AlreadyAcknowledged -> {
                // Branch is unreachable in the sample (consume=true never
                // short-circuits on isAcknowledged), but the sealed type's
                // exhaustiveness still requires the arm. For non-consumable
                // (consume=false) flows, treat AlreadyAcknowledged as a
                // grant signal — entitlement-equivalent to Success — and
                // log distinctly so telemetry can separate "we just acked"
                // from "it was already done by a prior session / sweep".
                appendLog("handlePurchase already-acked for ${purchase.products} (no PBL call made)")
                true
            }
            HandlePurchaseResult.NotPurchased -> {
                appendLog("handlePurchase skipped (not in PURCHASED state)")
                false
            }
            is HandlePurchaseResult.Failure -> {
                appendLog(
                    "handlePurchase FAILED: ${outcome.exception::class.simpleName} (${outcome.exception.userFacingCategory})"
                )
                false
            }
        }
    }

    fun loadProducts() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            try {
                val products = billing.queryProductDetails(testProductParams())
                _state.update { it.copy(loading = false, products = products) }
                appendLog("queried ${products.size} product(s)")
            } catch (e: BillingException) {
                _state.update { it.copy(loading = false) }
                appendLog("queryProductDetails FAILED: ${e::class.simpleName} (${e.retryType})")
            }
        }
    }

    /**
     * Demo path: simulate a server-side revocation (e.g. a refund processed by
     * Play that arrived via RTDN→FCM in a real app) by pushing a synthetic
     * [PurchaseRevoked] event through the same flow consumers already collect.
     * The library is transport-agnostic — `emitExternalRevocation` just routes
     * the event through the dedicated revocation replay-cache channel.
     */
    fun simulateRevocation() {
        viewModelScope.launch {
            billing.emitExternalRevocation(
                purchaseToken = "synthetic-token-${System.currentTimeMillis()}",
                reason = RevocationReason.Refunded,
            )
        }
    }

    fun buy(activity: Activity, productDetails: ProductDetails) {
        viewModelScope.launch {
            try {
                billing.launchFlow(activity, productDetails.toOneTimeFlowParams())
            } catch (e: BillingException) {
                appendLog("launchFlow FAILED: ${e::class.simpleName} (${e.retryType})")
            }
        }
    }

    private fun appendLog(line: String) {
        _state.update { it.copy(log = it.log + line) }
    }

    private fun testProductParams(): QueryProductDetailsParams =
        QueryProductDetailsParams.newBuilder()
            .setProductList(
                listOf(
                    QueryProductDetailsParams.Product.newBuilder()
                        .setProductId(TEST_PRODUCT_ID)
                        .setProductType(BillingClient.ProductType.INAPP)
                        .build()
                )
            )
            .build()

    companion object {
        // Google's static test SKU — works on any debug build with no Play Console
        // configuration. Always returns "purchased".
        const val TEST_PRODUCT_ID = "android.test.purchased"
    }
}

data class SampleUiState(
    val connection: BillingConnectionResult? = null,
    val products: List<ProductDetails> = emptyList(),
    val lastEvent: PurchaseEvent? = null,
    val loading: Boolean = false,
    val log: List<String> = emptyList(),
)
