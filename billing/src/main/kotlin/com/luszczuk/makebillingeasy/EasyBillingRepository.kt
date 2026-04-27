package com.luszczuk.makebillingeasy

import android.app.Activity
import android.util.Log
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.FeatureType
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResult
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.ProductDetailsResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResult
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.acknowledgePurchase
import com.android.billingclient.api.consumePurchase
import com.android.billingclient.api.queryPurchasesAsync
import com.luszczuk.makebillingeasy.exception.BillingException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapConcat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class EasyBillingRepository(
    private val billingClientStorage: BillingClientStorage,
    private val mainDispatcher: CoroutineDispatcher = Dispatchers.Main
) : BillingRepository {
    private val connectionFlowable
        get() = billingClientStorage.connectionFlow

    override fun connectToBilling(): SharedFlow<BillingConnectionResult> {
        return connectionFlowable
    }

    @ExperimentalCoroutinesApi
    override fun observePurchaseUpdates(): Flow<PurchasesUpdate> {
        return connectionFlowable.flatMapConcat {
            billingClientStorage.purchasesUpdateFlow
        }
    }

    @AnyThread
    override suspend fun isFeatureSupported(@FeatureType feature: String): Boolean {
        return connectToClientAndCall {
            getResultStatus(it.isFeatureSupported(feature).responseCode) == ResultStatus.SUCCESS
        }
    }

    @AnyThread
    override suspend fun queryPurchases(params: QueryPurchasesParams): List<Purchase> {
        return executeBillingOperation({ client -> client.queryPurchasesAsync(params) }).purchasesList
    }

    @AnyThread
    override suspend fun queryProductDetails(params: QueryProductDetailsParams): List<ProductDetails> {
        // Delegates to the unfetched-aware variant, which has strictly more information.
        // The list returned here omits any products Play couldn't fetch; callers that care
        // about diagnosing missing products should use [queryProductDetailsWithUnfetched].
        return queryProductDetailsWithUnfetched(params).productDetailsList
    }

    @AnyThread
    override suspend fun queryProductDetailsWithUnfetched(
        params: QueryProductDetailsParams
    ): QueryProductDetailsResult {
        // Wraps the callback-based queryProductDetailsAsync directly because the
        // billing-ktx 8.x suspend extension returns the legacy ProductDetailsResult,
        // which omits the unfetched list. Handed through executeBillingOperation so it
        // gets the same retry/backoff treatment as every other call.
        //
        // Resume behavior:
        //   * The cancellation-resume race is handled natively — CancellableContinuation
        //     .resume() silently absorbs the resume if the continuation has already been
        //     cancelled, so a late Play Billing callback after scope cancellation does
        //     NOT throw.
        //   * The one remaining scenario that could throw IllegalStateException is Play
        //     Billing firing the callback twice — a PBL bug, not something we expect, but
        //     cheap to defend against. Narrow try/catch here rather than opting into
        //     @InternalCoroutinesApi (tryResume/completeResume) keeps us off the
        //     internal-API treadmill.
        return executeBillingOperation({ client ->
            suspendCancellableCoroutine { cont ->
                client.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
                    try {
                        cont.resume(QueryProductDetailsResultWithBilling(billingResult, queryProductDetailsResult))
                    } catch (e: IllegalStateException) {
                        Log.w("MakeBillingEasy", "queryProductDetailsAsync callback fired after continuation was already resumed", e)
                    }
                }
            }
        }, mainDispatcher).queryProductDetailsResult
    }

    private data class QueryProductDetailsResultWithBilling(
        val billingResult: BillingResult,
        val queryProductDetailsResult: QueryProductDetailsResult
    )

    @AnyThread
    override suspend fun consumePurchase(params: ConsumeParams): String? {
        return executeBillingOperation({ client -> client.consumePurchase(params) }).purchaseToken
    }

    @AnyThread
    override suspend fun acknowledgePurchase(params: AcknowledgePurchaseParams) {
        executeBillingOperation({ client -> client.acknowledgePurchase(params) })
    }

    @UiThread
    override suspend fun launchFlow(activity: Activity, params: BillingFlowParams) {
        try {
            // Check that activity is still valid before launching billing flow
            if (activity.isFinishing || activity.isDestroyed) {
                Log.w("MakeBillingEasy", "Cannot launch billing flow - activity is no longer valid")
                val billingResult = BillingResult.newBuilder()
                    .setResponseCode(BillingResponseCode.DEVELOPER_ERROR)
                    .setDebugMessage("Attempted to launch billing flow with an invalid activity")
                    .build()
                throw BillingException.fromResult(billingResult)
            }

            executeBillingOperation({ client -> client.launchBillingFlow(activity, params) }, mainDispatcher)
        } catch (e: Exception) {
            // Re-throw the exception if it's already a BillingException, otherwise wrap it
            if (e !is BillingException) {
                val responseCode = if (e is NullPointerException) {
                    // This specifically addresses the ProxyBillingActivity crash with null PendingIntent
                    BillingResponseCode.SERVICE_UNAVAILABLE
                } else {
                    BillingResponseCode.ERROR
                }

                val billingResult = BillingResult.newBuilder()
                    .setResponseCode(responseCode)
                    .setDebugMessage("Unexpected error during billing flow: ${e.message}")
                    .build()

                BillingLoggingUtils.logBillingFlowFailure(
                    tag = "MakeBillingEasy",
                    billingResult = billingResult,
                    additionalContext = mapOf(
                        "ExceptionType" to e::class.simpleName,
                        "ActivityFinishing" to activity.isFinishing,
                        "ActivityDestroyed" to activity.isDestroyed
                    )
                )

                throw BillingException.fromResult(billingResult)
            } else {
                throw e
            }
        }
    }

    @AnyThread
    private suspend fun <T> executeBillingOperation(
        operation: suspend (client: BillingClient) -> T,
        dispatcher: CoroutineDispatcher = mainDispatcher
    ): T {
        return connectToClientAndCall { client ->
            withContext(dispatcher) {
                var result: T
                var billingResult: BillingResult
                var resultStatus: ResultStatus

                var attemptCount = 0
                // Per-call backoff state. Was previously held in the companion object,
                // which silently shared (and trampled) state across concurrent operations.
                var exponentialDelay = EXPONENTIAL_RETRY_INITIAL_DELAY

                var retryType = RetryType.NONE
                var prerequisiteSuccessful = false

                do {
                    attemptCount++

                    Log.d("MakeBillingEasy", "attempt $attemptCount starting")

                    result = operation(client)
                    billingResult = getBillingResult(result)
                    resultStatus = getResultStatus(billingResult.responseCode)

                    if (resultStatus != ResultStatus.SUCCESS && resultStatus != ResultStatus.CANCELED) {
                        Log.d("MakeBillingEasy", "attempt $attemptCount failed")

                        retryType = BillingException.fromResult(billingResult).retryType
                        prerequisiteSuccessful = handleRetryPrerequisite(retryType, exponentialDelay, dispatcher)
                        if (retryType == RetryType.EXPONENTIAL_RETRY) {
                            exponentialDelay *= EXPONENTIAL_RETRY_FACTOR
                        }
                    }
                } while (retryType != RetryType.NONE && attemptCount < EXPONENTIAL_RETRY_MAX_TRIES && prerequisiteSuccessful)

                Log.d("MakeBillingEasy", "call completed")

                if (resultStatus == ResultStatus.SUCCESS) {
                    Log.d("MakeBillingEasy", "Success: Operation successful")

                    result
                } else {
                    BillingLoggingUtils.logBillingFailure(
                        tag = "MakeBillingEasy",
                        billingResult = billingResult,
                        attemptCount = attemptCount,
                        operationContext = "Billing Operation",
                        additionalContext = mapOf(
                            "RetryType" to retryType,
                            "PrerequisiteSuccessful" to prerequisiteSuccessful
                        )
                    )

                    throw BillingException.fromResult(billingResult)
                }
            }
        }
    }

    private suspend fun <X : Any?> connectToClientAndCall(
        onSuccessfulConnection: suspend ((client: BillingClient) -> X)
    ): X {
        return when (val result = connectionFlowable.first()) {
            is BillingConnectionResult.Error -> throw result.exception
            is BillingConnectionResult.Success -> onSuccessfulConnection(result.client)
        }
    }

    private suspend fun handleRetryPrerequisite(
        retryType: RetryType,
        currentExponentialDelay: Long,
        dispatcher: CoroutineDispatcher
    ): Boolean {
        var retryPrerequisiteSuccessful = false

        when (retryType) {
            RetryType.SIMPLE_RETRY -> {
                Log.d("MakeBillingEasy", "Simple Retry")
                delay(SIMPLE_RETRY_DELAY)
                retryPrerequisiteSuccessful = true
            }

            RetryType.EXPONENTIAL_RETRY -> {
                Log.d("MakeBillingEasy", "Exponential Retry")
                delay(currentExponentialDelay)

                retryPrerequisiteSuccessful = true
            }

            RetryType.REQUERY_PURCHASE_RETRY -> {
                Log.d("MakeBillingEasy", "Requery Purchase Retry")

                val inAppPurchasesParams = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
                val subscriptionsParams = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()

                withContext(dispatcher) {
                    try {
                        async { queryPurchases(inAppPurchasesParams) }.await()
                        async { queryPurchases(subscriptionsParams) }.await()

                        retryPrerequisiteSuccessful = true

                        Log.d("MakeBillingEasy", "Requery Purchase Success")
                    } catch (ex: Exception) {
                        retryPrerequisiteSuccessful = false

                        // Enhanced logging for requery purchase failures
                        if (ex is BillingException) {
                            ex.result?.let { billingResult ->
                                BillingLoggingUtils.logBillingFailure(
                                    tag = "MakeBillingEasy",
                                    billingResult = billingResult,
                                    operationContext = "Requery Purchase Retry",
                                    additionalContext = mapOf(
                                        "RetryType" to RetryType.REQUERY_PURCHASE_RETRY.name
                                    )
                                )
                            } ?: Log.w("MakeBillingEasy", "Requery Purchase Failure: BillingException with null result", ex)
                        } else {
                            Log.w("MakeBillingEasy", "Requery Purchase Failure", ex)
                        }
                    }
                }
            }

            else -> return true
        }

        return retryPrerequisiteSuccessful
    }

    private fun <T> getBillingResult(result: T): BillingResult {
        return when (result) {
            is BillingResult -> result
            is PurchasesResult -> result.billingResult
            is ProductDetailsResult -> result.billingResult
            is ConsumeResult -> result.billingResult
            is QueryProductDetailsResultWithBilling -> result.billingResult
            else -> {
                // Defaults to ERROR, not OK. The empty BillingResult() constructor
                // silently returns responseCode=0 (OK), which would make an unhandled
                // result type look like success. Any new result shape must be added
                // to the list above.
                val typeName = result?.let { it::class.simpleName } ?: "null"
                BillingResult.newBuilder()
                    .setResponseCode(BillingResponseCode.ERROR)
                    .setDebugMessage("Unhandled billing result type: $typeName")
                    .build()
            }
        }
    }

    private fun getResultStatus(responseCode: Int): ResultStatus {
        return when (responseCode) {
            BillingResponseCode.OK ->
                ResultStatus.SUCCESS

            BillingResponseCode.USER_CANCELED ->
                ResultStatus.CANCELED

            else ->
                ResultStatus.ERROR
        }
    }

    companion object {
        private const val EXPONENTIAL_RETRY_MAX_TRIES: Int = 4
        private const val EXPONENTIAL_RETRY_INITIAL_DELAY: Long = 2000L
        private const val EXPONENTIAL_RETRY_FACTOR: Int = 2

        private const val SIMPLE_RETRY_DELAY: Long = 500L
    }
}
