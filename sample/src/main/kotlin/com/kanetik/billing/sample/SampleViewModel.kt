package com.kanetik.billing.sample

import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.QueryProductDetailsParams
import com.kanetik.billing.BillingConnectionResult
import com.kanetik.billing.BillingRepository
import com.kanetik.billing.BillingRepositoryCreator
import com.kanetik.billing.HandlePurchaseResult
import com.kanetik.billing.PurchasesUpdate
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
            billing.observePurchaseUpdates().collect { update ->
                _state.update { it.copy(lastUpdate = update) }
                appendLog("purchase update: ${update::class.simpleName}")
                when (update) {
                    is PurchasesUpdate.Success -> update.purchases.forEach { handlePurchaseAndLog(it) }
                    is PurchasesUpdate.Recovered -> update.purchases.forEach { handlePurchaseAndLog(it) }
                    else -> {} // Pending / Canceled / etc. — sample just logs the variant name above
                }
            }
        }
    }

    /**
     * For `android.test.purchased`, consume = true so a fresh run can re-purchase.
     * For non-consumable IAP in real apps, pass consume = false to acknowledge-only.
     * Real apps would also grant entitlement on HandlePurchaseResult.Success — this
     * sample just logs the outcome.
     */
    private suspend fun handlePurchaseAndLog(purchase: com.android.billingclient.api.Purchase) {
        when (val outcome = billing.handlePurchase(purchase, consume = true)) {
            HandlePurchaseResult.Success ->
                appendLog("handlePurchase OK for ${purchase.products}")
            HandlePurchaseResult.NotPurchased ->
                appendLog("handlePurchase skipped (not in PURCHASED state)")
            is HandlePurchaseResult.Failure ->
                appendLog(
                    "handlePurchase FAILED: ${outcome.exception::class.simpleName} (${outcome.exception.userFacingCategory})"
                )
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
    val lastUpdate: PurchasesUpdate? = null,
    val loading: Boolean = false,
    val log: List<String> = emptyList(),
)
