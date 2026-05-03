package com.kanetik.billing

import android.app.Activity
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
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResult
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
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

internal class DefaultBillingRepository(
    private val billingClientStorage: BillingClientStorage,
    private val logger: BillingLogger = BillingLogger.Noop,
    // ioDispatcher carries every non-UI billing operation (queries, consume,
    // acknowledge) and the surrounding retry/backoff loop. uiDispatcher is
    // used only by launchFlow because PBL requires launchBillingFlow on Main.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
) : BillingRepository {
    override fun connectToBilling(): SharedFlow<BillingConnectionResult> {
        return billingClientStorage.connectionResultFlow
    }

    override fun observePurchaseUpdates(): Flow<PurchasesUpdate> {
        // Hot at the listener level — PBL fires the PurchasesUpdatedListener
        // regardless of whether anyone's collecting our connection flow. The
        // backing flows in BillingClientStorage are SharedFlows so emissions
        // aren't tied to subscriber attachment; the Flow returned here merges
        // the live and recovery channels (see BillingClientStorage's two-channel
        // architecture comment for why the split exists).
        return billingClientStorage.purchasesUpdateFlow
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
        return queryProductDetailsWithUnfetched(params).productDetails
    }

    @AnyThread
    override suspend fun queryProductDetailsWithUnfetched(
        params: QueryProductDetailsParams
    ): ProductDetailsQuery {
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
        val raw = executeBillingOperation({ client ->
            suspendCancellableCoroutine { cont ->
                client.queryProductDetailsAsync(params) { billingResult, queryProductDetailsResult ->
                    try {
                        cont.resume(QueryProductDetailsResultWithBilling(billingResult, queryProductDetailsResult))
                    } catch (e: IllegalStateException) {
                        logger.w("queryProductDetailsAsync callback fired after continuation was already resumed", e)
                    }
                }
            }
        }).queryProductDetailsResult

        return ProductDetailsQuery(
            productDetails = raw.productDetailsList,
            unfetchedProducts = raw.unfetchedProductList
        )
    }

    private data class QueryProductDetailsResultWithBilling(
        val billingResult: BillingResult,
        val queryProductDetailsResult: QueryProductDetailsResult
    )

    @AnyThread
    override suspend fun consumePurchase(params: ConsumeParams): String {
        // executeBillingOperation throws BillingException on non-success — if we get
        // a result back the consume succeeded, and PBL guarantees the token is set
        // on success. The !! guards against an unexpected PBL contract violation
        // by failing loudly rather than returning a phantom null.
        return executeBillingOperation({ client -> client.consumePurchase(params) }).purchaseToken!!
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
                logger.w("Cannot launch billing flow - activity is no longer valid")
                val billingResult = BillingResult.newBuilder()
                    .setResponseCode(BillingResponseCode.DEVELOPER_ERROR)
                    .setDebugMessage("Attempted to launch billing flow with an invalid activity")
                    .build()
                throw BillingException.fromResult(billingResult)
            }

            // launchFlow is a UI-initiated action; silently retrying the billing sheet
            // behind the user's back risks surprise pop-ups after they've moved on.
            // Single attempt — the user can tap Buy again if it didn't take.
            executeBillingOperation(
                operation = { client -> client.launchBillingFlow(activity, params) },
                dispatcher = uiDispatcher,
                maxAttempts = 1
            )
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
                    logger = logger,
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

    @UiThread
    override suspend fun showInAppMessages(
        activity: Activity,
        params: InAppMessageParams
    ): BillingInAppMessageResult = connectToClientAndCall { client ->
        withContext(uiDispatcher) {
            suspendCancellableCoroutine { cont ->
                val billingResult = client.showInAppMessages(activity, params) { result ->
                    try {
                        cont.resume(mapInAppMessageResult(result))
                    } catch (e: IllegalStateException) {
                        logger.w(
                            "showInAppMessages callback fired after continuation was already resumed",
                            e
                        )
                    }
                }
                if (billingResult.responseCode != BillingResponseCode.OK) {
                    cont.resumeWith(
                        Result.failure(BillingException.fromResult(billingResult))
                    )
                }
            }
        }
    }

    private fun mapInAppMessageResult(result: InAppMessageResult): BillingInAppMessageResult {
        return when (result.responseCode) {
            InAppMessageResult.InAppMessageResponseCode.SUBSCRIPTION_STATUS_UPDATED -> {
                val token = result.purchaseToken
                if (token != null) {
                    BillingInAppMessageResult.SubscriptionStatusUpdated(token)
                } else {
                    logger.w(
                        "In-app message reported SUBSCRIPTION_STATUS_UPDATED with null purchaseToken — falling back to NoActionNeeded"
                    )
                    BillingInAppMessageResult.NoActionNeeded
                }
            }
            else -> BillingInAppMessageResult.NoActionNeeded
        }
    }

    @AnyThread
    private suspend fun <T> executeBillingOperation(
        operation: suspend (client: BillingClient) -> T,
        dispatcher: CoroutineDispatcher = ioDispatcher,
        maxAttempts: Int = EXPONENTIAL_RETRY_MAX_TRIES
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

                    logger.d("attempt $attemptCount starting")

                    result = operation(client)
                    billingResult = getBillingResult(result)
                    resultStatus = getResultStatus(billingResult.responseCode)

                    if (resultStatus != ResultStatus.SUCCESS && resultStatus != ResultStatus.CANCELED) {
                        logger.d("attempt $attemptCount failed")

                        retryType = BillingException.fromResult(billingResult).retryType
                        prerequisiteSuccessful = handleRetryPrerequisite(retryType, exponentialDelay, dispatcher)
                        if (retryType == RetryType.EXPONENTIAL_RETRY) {
                            exponentialDelay *= EXPONENTIAL_RETRY_FACTOR
                        }
                    }
                } while (retryType != RetryType.NONE && attemptCount < maxAttempts && prerequisiteSuccessful)

                logger.d("call completed")

                if (resultStatus == ResultStatus.SUCCESS) {
                    logger.d("Success: Operation successful")

                    result
                } else {
                    BillingLoggingUtils.logBillingFailure(
                        logger = logger,
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
        // withTimeout guards against the SharedFlow's upstream completing without
        // emitting (e.g. scope cancellation that bypasses the .catch handler). Without
        // this, first() would suspend forever with no error and no recovery path.
        val state = try {
            withTimeout(CONNECTION_TIMEOUT_MS) {
                billingClientStorage.connectionFlow.first()
            }
        } catch (e: TimeoutCancellationException) {
            val timeoutResult = BillingResult.newBuilder()
                .setResponseCode(BillingResponseCode.SERVICE_UNAVAILABLE)
                .setDebugMessage("Billing connection didn't resolve within ${CONNECTION_TIMEOUT_MS}ms")
                .build()
            throw BillingException.fromResult(timeoutResult)
        }
        return when (state) {
            is InternalConnectionState.Failed -> throw state.exception
            is InternalConnectionState.Connected -> onSuccessfulConnection(state.client)
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
                logger.d("Simple Retry")
                delay(SIMPLE_RETRY_DELAY)
                retryPrerequisiteSuccessful = true
            }

            RetryType.EXPONENTIAL_RETRY -> {
                logger.d("Exponential Retry")
                delay(currentExponentialDelay)

                retryPrerequisiteSuccessful = true
            }

            RetryType.REQUERY_PURCHASE_RETRY -> {
                logger.d("Requery Purchase Retry")

                val inAppPurchasesParams = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
                val subscriptionsParams = QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.SUBS).build()

                withContext(dispatcher) {
                    try {
                        async { queryPurchases(inAppPurchasesParams) }.await()
                        async { queryPurchases(subscriptionsParams) }.await()

                        retryPrerequisiteSuccessful = true

                        logger.d("Requery Purchase Success")
                    } catch (ex: Exception) {
                        retryPrerequisiteSuccessful = false

                        // Enhanced logging for requery purchase failures
                        if (ex is BillingException) {
                            ex.result?.let { billingResult ->
                                BillingLoggingUtils.logBillingFailure(
                                    logger = logger,
                                    billingResult = billingResult,
                                    operationContext = "Requery Purchase Retry",
                                    additionalContext = mapOf(
                                        "RetryType" to RetryType.REQUERY_PURCHASE_RETRY.name
                                    )
                                )
                            } ?: logger.w("Requery Purchase Failure: BillingException with null result", ex)
                        } else {
                            logger.w("Requery Purchase Failure", ex)
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

        // Generous enough for slow Play Billing setups, short enough to surface a
        // hung connection rather than suspending the caller forever.
        private const val CONNECTION_TIMEOUT_MS: Long = 30_000L
    }
}
