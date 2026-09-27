package com.kanetik.billing

import android.app.Activity
import androidx.annotation.AnyThread
import androidx.annotation.UiThread
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingChoiceInfo
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClient.FeatureType
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.BillingProgramAvailabilityDetails
import com.android.billingclient.api.BillingProgramAvailabilityDetails.BillingChoiceAvailabilityDetails.ChoiceScreenType as PblChoiceScreenType
import com.android.billingclient.api.BillingProgramInformationDialogParams
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResult
import com.android.billingclient.api.GetBillingChoiceInfoParams
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
import com.kanetik.billing.choice.BillingChoiceAvailability
import com.kanetik.billing.choice.BillingChoiceDetails
import com.kanetik.billing.choice.ChoiceScreenType
import com.kanetik.billing.choice.ExperimentalBillingChoiceApi
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal class DefaultBillingRepository(
    private val billingClientStorage: BillingClientStorage,
    private val logger: BillingLogger = BillingLogger.Noop,
    // ioDispatcher carries every non-UI billing operation (queries, consume,
    // acknowledge) and the surrounding retry/backoff loop. uiDispatcher is
    // used only by launchFlow because PBL requires launchBillingFlow on Main.
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
    // Backs queryBillingAvailability's deterministic UNAVAILABLE verdict.
    // Defaults to "usable" so tests that construct the repository directly and
    // don't exercise availability keep their previous behavior.
    private val playStoreEnvironment: PlayStoreEnvironment = PlayStoreEnvironment { true }
) : BillingRepository {
    override fun connectToBilling(): SharedFlow<BillingConnectionResult> {
        return billingClientStorage.connectionResultFlow
    }

    @AnyThread
    override suspend fun queryBillingAvailability(): BillingAvailability {
        // Step 1: deterministic, offline check. If the Play Store package is
        // absent or disabled, billing can never work here — a stable, terminal
        // fact the caller can safely act on.
        if (!playStoreEnvironment.isPlayStoreUsable()) {
            logger.d("queryBillingAvailability: Play Store package absent/disabled -> UNAVAILABLE")
            return BillingAvailability.UNAVAILABLE
        }
        // Step 2: Play Store is present. Try to connect. A success is a clean
        // AVAILABLE; ANY failure here (including a transient BILLING_UNAVAILABLE
        // / code 3, a slow cold start, or a dropped connection) is reported as
        // UNKNOWN, never UNAVAILABLE — the Play Store exists, so the failure is
        // not the terminal "this device can't pay" verdict.
        // withTimeoutOrNull (not withTimeout + catch): its own deadline maps to
        // null -> UNKNOWN, while an OUTER timeout/cancellation imposed by the
        // caller is a different TimeoutCancellationException instance that is NOT
        // swallowed here — it propagates, so a caller wrapping this in its own
        // withTimeout keeps control of its deadline/cancellation contract.
        val result = withTimeoutOrNull(AVAILABILITY_CONNECT_TIMEOUT_MS) {
            awaitConnection()
        }
        return when (result) {
            is InternalConnectionState.Connected -> BillingAvailability.AVAILABLE
            is InternalConnectionState.Failed -> {
                logger.d(
                    "queryBillingAvailability: Play Store present but connection failed " +
                        "(${result.exception::class.simpleName}) -> UNKNOWN"
                )
                BillingAvailability.UNKNOWN
            }
            null -> {
                logger.d("queryBillingAvailability: connection attempt timed out -> UNKNOWN")
                BillingAvailability.UNKNOWN
            }
        }
    }

    override fun observePurchaseUpdates(): Flow<PurchaseEvent> {
        // Hot at the listener level — PBL fires the PurchasesUpdatedListener
        // regardless of whether anyone's collecting our connection flow. The
        // backing flows in BillingClientStorage are SharedFlows so emissions
        // aren't tied to subscriber attachment; the Flow returned here merges
        // the four channels (live PBL events, recovery sweeps, refreshPurchases()
        // snapshots, and external revocations) — see BillingClientStorage's
        // channel-architecture comment for why the split exists.
        return billingClientStorage.purchasesUpdateFlow
    }

    @AnyThread
    override suspend fun emitExternalRevocation(purchaseToken: String, reason: RevocationReason) {
        // Routed through the dedicated revocation channel (replay = 16) — see
        // BillingClientStorage.emitExternalRevocation and the BillingRepository
        // interface KDoc for why.
        billingClientStorage.emitExternalRevocation(purchaseToken, reason)
    }

    @AnyThread
    override suspend fun isFeatureSupported(@FeatureType feature: String): Boolean {
        return try {
            executeBillingOperation(RetryProfile.INTERACTIVE, { client -> client.isFeatureSupported(feature) })
            true
        } catch (e: BillingException.FeatureNotSupportedException) {
            false
        }
    }

    @AnyThread
    override suspend fun queryPurchases(params: QueryPurchasesParams): List<Purchase> {
        return executeBillingOperation(RetryProfile.BACKGROUND, { client -> client.queryPurchasesAsync(params) }).purchasesList
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
        // billing-ktx 9.x suspend extension returns the legacy ProductDetailsResult,
        // which omits the unfetched list.
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
        val raw = executeBillingOperation(RetryProfile.INTERACTIVE, { client ->
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
        val token = try {
            executeBillingOperation(RetryProfile.BACKGROUND, { client -> client.consumePurchase(params) }).purchaseToken!!
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: BillingException) {
            billingClientStorage.scheduleFailedAcknowledgeRetry()
            throw e
        }
        // Record the token so the recovery sweep filters this purchase out of
        // future Recovered emissions (Play treats consume as implicit
        // acknowledgement for consumables; subsequent sweeps still see the
        // token until Play removes the purchase from query results).
        billingClientStorage.markAcknowledged(token)
        return token
    }

    @AnyThread
    override suspend fun acknowledgePurchase(params: AcknowledgePurchaseParams) {
        try {
            executeBillingOperation(RetryProfile.BACKGROUND, { client -> client.acknowledgePurchase(params) })
        } catch (ce: kotlinx.coroutines.CancellationException) {
            throw ce
        } catch (e: BillingException) {
            billingClientStorage.scheduleFailedAcknowledgeRetry()
            throw e
        }
        // Record the token only after a successful acknowledge. A failure
        // throws above; suppressing the next sweep on a failed ack would
        // orphan the purchase.
        billingClientStorage.markAcknowledged(params.purchaseToken)
    }

    @AnyThread
    override suspend fun handlePurchase(purchase: Purchase, consume: Boolean): HandlePurchaseResult {
        if (!consume &&
            purchase.purchaseState == Purchase.PurchaseState.PURCHASED &&
            billingClientStorage.isMarkedAcknowledged(purchase.purchaseToken)
        ) {
            return HandlePurchaseResult.AlreadyAcknowledged
        }
        return super.handlePurchase(purchase, consume)
    }

    @AnyThread
    override suspend fun refreshPurchases() {
        connectToClientAndCall { client -> billingClientStorage.refreshOwnedPurchases(client) }
    }

    @UiThread
    override suspend fun launchFlow(activity: Activity, params: BillingFlowParams) {
        try {
            // Check that activity is still valid before launching billing flow
            if (activity.isFinishing || activity.isDestroyed) {
                logger.e("Cannot launch billing flow - activity is no longer valid")
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
                profile = RetryProfile.SINGLE_ATTEMPT,
                operation = { client -> client.launchBillingFlow(activity, params) },
                dispatcher = uiDispatcher
            )
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Must come before the broad Exception catch below — otherwise a
            // CancellationException raised by structured cancellation would get
            // wrapped into a BillingException and silently break the scope teardown.
            // Matches the rethrow contract every other suspend member upholds.
            throw ce
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
                    BillingLoggingUtils.logBillingFailure(logger, billingResult, operationContext = "In-App Messages")
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

    // --- Billing Choice (experimental, PBL 9.1.0) ---------------------------
    // Thin mirrors of PBL's availability -> info -> dialog surface. Each reuses
    // connectToClientAndCall (connection wait) + suspendCancellableCoroutine,
    // same as showInAppMessages above. The @OptIn on each override is required
    // because the BillingChoiceActions members carry the experimental marker.

    @AnyThread
    @OptIn(ExperimentalBillingChoiceApi::class)
    override suspend fun isBillingChoiceAvailable(): BillingChoiceAvailability =
        connectToClientAndCall { client ->
            withContext(ioDispatcher) {
                suspendCancellableCoroutine { cont ->
                    client.isBillingProgramAvailableAsync(
                        BillingClient.BillingProgram.BILLING_CHOICE
                    ) { billingResult, details ->
                        try {
                            cont.resume(mapBillingChoiceAvailability(billingResult, details))
                        } catch (e: IllegalStateException) {
                            logger.w(
                                "isBillingProgramAvailableAsync callback fired after continuation was already resumed",
                                e
                            )
                        }
                    }
                }
            }
        }

    @AnyThread
    @OptIn(ExperimentalBillingChoiceApi::class)
    override suspend fun getBillingChoiceInfo(
        params: GetBillingChoiceInfoParams
    ): BillingChoiceDetails = connectToClientAndCall { client ->
        withContext(ioDispatcher) {
            suspendCancellableCoroutine { cont ->
                client.getBillingChoiceInfoAsync(params) { billingResult, info ->
                    try {
                        if (billingResult.responseCode == BillingResponseCode.OK) {
                            cont.resume(mapBillingChoiceDetails(info))
                        } else {
                            BillingLoggingUtils.logBillingFailure(logger, billingResult, operationContext = "Billing Choice Info")
                            cont.resumeWith(
                                Result.failure(BillingException.fromResult(billingResult))
                            )
                        }
                    } catch (e: IllegalStateException) {
                        logger.w(
                            "getBillingChoiceInfoAsync callback fired after continuation was already resumed",
                            e
                        )
                    }
                }
            }
        }
    }

    @UiThread
    @OptIn(ExperimentalBillingChoiceApi::class)
    override suspend fun showBillingProgramInformationDialog(
        activity: Activity,
        params: BillingProgramInformationDialogParams
    ): Unit = connectToClientAndCall { client ->
        withContext(uiDispatcher) {
            suspendCancellableCoroutine { cont ->
                client.showBillingProgramInformationDialog(activity, params) { billingResult ->
                    try {
                        if (billingResult.responseCode == BillingResponseCode.OK) {
                            cont.resume(Unit)
                        } else {
                            BillingLoggingUtils.logBillingFailure(
                                logger,
                                billingResult,
                                operationContext = "Billing Program Information Dialog"
                            )
                            cont.resumeWith(
                                Result.failure(BillingException.fromResult(billingResult))
                            )
                        }
                    } catch (e: IllegalStateException) {
                        logger.w(
                            "showBillingProgramInformationDialog callback fired after continuation was already resumed",
                            e
                        )
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalBillingChoiceApi::class)
    private fun mapBillingChoiceAvailability(
        billingResult: BillingResult,
        details: BillingProgramAvailabilityDetails
    ): BillingChoiceAvailability {
        if (billingResult.responseCode != BillingResponseCode.OK) {
            logger.d(
                "Billing Choice availability query returned ${billingResult.responseCode} — reporting Unavailable"
            )
            return BillingChoiceAvailability.Unavailable
        }
        val choice = details.billingChoiceAvailabilityDetails
            ?: return BillingChoiceAvailability.Unavailable
        return BillingChoiceAvailability.Available(
            choiceScreenType = mapChoiceScreenType(choice.choiceScreenType),
            externalLinkAvailable = choice.isExternalLinkAvailable
        )
    }

    @OptIn(ExperimentalBillingChoiceApi::class)
    private fun mapBillingChoiceDetails(info: BillingChoiceInfo?): BillingChoiceDetails =
        BillingChoiceDetails(
            imageUrl = info?.playBillingChoiceImageUrl,
            loyaltyInfo = info?.playBillingLoyaltyInfo
        )

    @OptIn(ExperimentalBillingChoiceApi::class)
    private fun mapChoiceScreenType(code: Int): ChoiceScreenType = when (code) {
        PblChoiceScreenType.DEVELOPER_RENDERED -> ChoiceScreenType.DEVELOPER_RENDERED
        PblChoiceScreenType.GOOGLE_RENDERED -> ChoiceScreenType.GOOGLE_RENDERED
        PblChoiceScreenType.UNSPECIFIED -> ChoiceScreenType.UNSPECIFIED
        else -> ChoiceScreenType.UNKNOWN
    }

    @AnyThread
    private suspend fun <T> executeBillingOperation(
        profile: RetryProfile,
        operation: suspend (client: BillingClient) -> T,
        dispatcher: CoroutineDispatcher = ioDispatcher
    ): T = withContext(dispatcher) {
        retryBillingCall(profile, logger, { getBillingResult(it) }) {
            connectToClientAndCall { client -> operation(client) }
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
                awaitConnection()
            }
        } catch (e: TimeoutCancellationException) {
            val timeoutResult = BillingResult.newBuilder()
                .setResponseCode(BillingResponseCode.SERVICE_UNAVAILABLE)
                .setDebugMessage("Billing connection didn't resolve within ${CONNECTION_TIMEOUT_MS}ms")
                .build()
            BillingLoggingUtils.logBillingFailure(logger, timeoutResult, operationContext = "Billing Connection")
            throw BillingException.fromResult(timeoutResult)
        }
        return when (state) {
            is InternalConnectionState.Failed -> throw state.exception
            is InternalConnectionState.Connected -> onSuccessfulConnection(state.client)
        }
    }

    private suspend fun awaitConnection(): InternalConnectionState {
        val cached = billingClientStorage.connectionFlow.replayCache.lastOrNull()
        val connection = billingClientStorage.connectionFlow.filterNotNull()
        val state = connection.first()
        when (state) {
            is InternalConnectionState.Connected -> if (billingClientStorage.isLive(state.client)) return state
            is InternalConnectionState.Failed -> {
                if (state !== cached) return state
                billingClientStorage.requestReconnect(state)
            }
        }
        return connection.first { it !== state }
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

    companion object {
        // Generous enough for slow Play Billing setups, short enough to surface a
        // hung connection rather than suspending the caller forever.
        private const val CONNECTION_TIMEOUT_MS: Long = 30_000L

        // queryBillingAvailability bounds its connection attempt so a hung/cold
        // connect resolves to UNKNOWN rather than suspending the caller. Shorter
        // than CONNECTION_TIMEOUT_MS because availability is a UI-gating probe
        // (the caller is deciding what to show now), not a purchase-critical op.
        private const val AVAILABILITY_CONNECT_TIMEOUT_MS: Long = 8_000L
    }
}
