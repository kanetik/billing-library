package com.kanetik.billing.ext

import android.app.Activity
import com.android.billingclient.api.ProductDetails
import com.kanetik.billing.BillingRepository
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Wraps [BillingRepository.launchFlow] with three universally-useful safeguards:
 *
 * 1. **Single-in-flight guard**: an [AtomicBoolean] prevents a second concurrent
 *    launch attempt — a Play Billing flow already in progress will silently swallow
 *    a new `launchBillingFlow` call, leaving the second caller in an unresolved
 *    state. The coordinator returns [PurchaseFlowResult.AlreadyInProgress] instead.
 * 2. **Watchdog timer**: defaults to 2 minutes. Play Billing occasionally hangs
 *    silently — a flow launches, no purchase update ever arrives, and the
 *    in-flight flag stays set forever, preventing the user from re-trying. The
 *    watchdog clears the flag after [watchdogTimeoutMs].
 * 3. **Correlation-id logging**: every flow attempt gets a UUID logged via [logger]
 *    so you can trace one user-facing purchase attempt across log lines.
 *
 * ## Usage
 *
 * ```
 * private val coordinator = PurchaseFlowCoordinator(
 *     billingRepository = billing,
 *     scope = viewModelScope,
 *     logger = MyBillingLogger()
 * )
 *
 * suspend fun onBuyClicked(activity: Activity, product: ProductDetails) {
 *     when (val result = coordinator.launch(activity, product, supportId)) {
 *         PurchaseFlowResult.Success -> { /* observe purchase updates */ }
 *         PurchaseFlowResult.AlreadyInProgress -> { /* user double-tapped */ }
 *         PurchaseFlowResult.InvalidActivityState -> { /* activity gone */ }
 *         PurchaseFlowResult.BillingUnavailable -> { /* show fallback UI */ }
 *         is PurchaseFlowResult.Error -> { /* report result.cause */ }
 *     }
 * }
 *
 * // When you receive a terminal PurchasesUpdate (Success / Canceled / etc.),
 * // tell the coordinator the flow is done so the next launch can proceed:
 * billing.observePurchaseUpdates().collect { update ->
 *     coordinator.markComplete()
 *     // ... handle update
 * }
 * ```
 *
 * ## What it does NOT do
 *
 * - Doesn't acknowledge or consume purchases — that's the caller's job once a
 *   [com.kanetik.billing.PurchasesUpdate.Success] arrives.
 * - Doesn't decide premium-grant rules — that's app business logic.
 * - Doesn't track analytics events — wrap [launch] with your own analytics layer
 *   if needed.
 *
 * @param billingRepository The active [BillingRepository] (typically from
 *   [com.kanetik.billing.BillingRepositoryCreator]).
 * @param scope A [CoroutineScope] used to launch the watchdog. Use a scope that
 *   outlives the activity (e.g. a ViewModel or application scope) so the
 *   watchdog can clear the flag even if the launching screen goes away.
 * @param logger Optional logger for correlation-id traces. Defaults to silent.
 * @param watchdogTimeoutMs How long to wait before assuming a launched flow is
 *   abandoned. Defaults to 2 minutes.
 */
public class PurchaseFlowCoordinator(
    private val billingRepository: BillingRepository,
    private val scope: CoroutineScope,
    private val logger: BillingLogger = BillingLogger.Noop,
    private val watchdogTimeoutMs: Long = DEFAULT_WATCHDOG_TIMEOUT_MS
) {
    private val isPurchaseFlowInProgress = AtomicBoolean(false)

    /**
     * Launches a one-time-product purchase flow against Play Billing.
     *
     * @param activity The [Activity] hosting the purchase. Must be in a valid
     *   state per [validatePurchaseActivity]; otherwise returns
     *   [PurchaseFlowResult.InvalidActivityState].
     * @param productDetails The product to purchase, queried via
     *   [com.kanetik.billing.BillingActions.queryProductDetails].
     * @param obfuscatedAccountId Optional stable per-install identifier passed
     *   through to Play for fraud-detection correlation. See
     *   [ProductDetails.toOneTimeFlowParams] for the contract.
     */
    public suspend fun launch(
        activity: Activity,
        productDetails: ProductDetails,
        obfuscatedAccountId: String? = null
    ): PurchaseFlowResult {
        val correlationId = UUID.randomUUID().toString()
        logger.d("PurchaseFlow[$correlationId]: attempt")

        if (!validatePurchaseActivity(activity)) {
            logger.w("PurchaseFlow[$correlationId]: invalid activity state")
            return PurchaseFlowResult.InvalidActivityState
        }

        if (!isPurchaseFlowInProgress.compareAndSet(false, true)) {
            logger.d("PurchaseFlow[$correlationId]: already in progress")
            return PurchaseFlowResult.AlreadyInProgress
        }

        var launched = false
        return try {
            val flowParams = productDetails.toOneTimeFlowParams(obfuscatedAccountId)
            withContext(Dispatchers.Main) {
                billingRepository.launchFlow(activity, flowParams)
            }
            launched = true
            logger.d("PurchaseFlow[$correlationId]: launched successfully")
            startWatchdog(correlationId)
            PurchaseFlowResult.Success
        } catch (ce: CancellationException) {
            if (!launched) isPurchaseFlowInProgress.set(false)
            logger.d("PurchaseFlow[$correlationId]: cancelled", ce)
            throw ce
        } catch (e: BillingException.BillingUnavailableException) {
            isPurchaseFlowInProgress.set(false)
            logger.w("PurchaseFlow[$correlationId]: billing unavailable", e)
            PurchaseFlowResult.BillingUnavailable
        } catch (t: Throwable) {
            isPurchaseFlowInProgress.set(false)
            logger.e("PurchaseFlow[$correlationId]: launch failed", t)
            PurchaseFlowResult.Error(t)
        }
    }

    /**
     * Clears the in-flight guard. Call this when you receive a terminal
     * [com.kanetik.billing.PurchasesUpdate] (Success, Canceled, ItemAlreadyOwned,
     * ItemUnavailable, UnknownResponse) so the next [launch] call isn't blocked.
     *
     * Safe to call when no flow is in progress — it's a no-op in that case.
     */
    public fun markComplete() {
        isPurchaseFlowInProgress.set(false)
    }

    private fun startWatchdog(correlationId: String) {
        scope.launch {
            delay(watchdogTimeoutMs)
            if (isPurchaseFlowInProgress.get()) {
                isPurchaseFlowInProgress.set(false)
                logger.w("PurchaseFlow[$correlationId]: watchdog reset triggered after ${watchdogTimeoutMs}ms")
            }
        }
    }

    public companion object {
        public const val DEFAULT_WATCHDOG_TIMEOUT_MS: Long = 2 * 60 * 1000L
    }
}

/**
 * Outcome of a single [PurchaseFlowCoordinator.launch] call.
 *
 * Note that [Success] only means **the flow successfully launched** — the actual
 * purchase outcome (paid, canceled, already owned, etc.) arrives separately via
 * [com.kanetik.billing.BillingPurchaseUpdatesOwner.observePurchaseUpdates].
 */
public sealed class PurchaseFlowResult {
    public object Success : PurchaseFlowResult()
    public object AlreadyInProgress : PurchaseFlowResult()
    public object InvalidActivityState : PurchaseFlowResult()
    public object BillingUnavailable : PurchaseFlowResult()
    public data class Error(val cause: Throwable) : PurchaseFlowResult()
}
