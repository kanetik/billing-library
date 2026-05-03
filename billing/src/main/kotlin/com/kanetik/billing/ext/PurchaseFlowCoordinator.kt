package com.kanetik.billing.ext

import android.app.Activity
import com.android.billingclient.api.ProductDetails
import com.kanetik.billing.BillingRepository
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
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
 * @param uiDispatcher Dispatcher used to wrap the call into
 *   [BillingRepository.launchFlow][com.kanetik.billing.BillingActions.launchFlow]
 *   — Play Billing requires `launchBillingFlow` to run on the main thread.
 *   Defaults to [Dispatchers.Main]. **This wrap is defensive only**: the
 *   default [com.kanetik.billing.DefaultBillingRepository] does its own
 *   internal `withContext(uiDispatcher)` hop using the dispatcher passed to
 *   [com.kanetik.billing.BillingRepositoryCreator.create]. Overriding the
 *   coordinator's `uiDispatcher` in isolation **does not** make the default
 *   path test-synchronous — the repository's hop still lands on whatever
 *   dispatcher it was created with. To synchronize the default path under
 *   virtual time in tests, set the repository's dispatcher too
 *   (`BillingRepositoryCreator.create(uiDispatcher = testDispatcher)`) or
 *   redirect Main globally via `Dispatchers.setMain(testDispatcher)`. The
 *   coordinator's own `uiDispatcher` exists so custom `BillingRepository`
 *   implementations that follow PBL's `@MainThread` contract literally
 *   without internal dispatch still get hopped to the right thread.
 */
public class PurchaseFlowCoordinator(
    private val billingRepository: BillingRepository,
    private val scope: CoroutineScope,
    private val logger: BillingLogger = BillingLogger.Noop,
    private val watchdogTimeoutMs: Long = DEFAULT_WATCHDOG_TIMEOUT_MS,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main
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
     * @param obfuscatedProfileId Optional secondary opaque ID for apps with
     *   multiple user profiles per install. See
     *   [ProductDetails.toOneTimeFlowParams] for the contract.
     */
    public suspend fun launch(
        activity: Activity,
        productDetails: ProductDetails,
        obfuscatedAccountId: String? = null,
        obfuscatedProfileId: String? = null
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
            val flowParams = productDetails.toOneTimeFlowParams(
                obfuscatedAccountId = obfuscatedAccountId,
                obfuscatedProfileId = obfuscatedProfileId
            )
            // Defensive Main hop. The default DefaultBillingRepository.launchFlow
            // already does its own withContext(uiDispatcher) internally, so this
            // is redundant for that impl. But PurchaseFlowCoordinator is public
            // and accepts ANY BillingRepository — a custom impl that follows
            // PBL's @MainThread contract literally without dispatching internally
            // would otherwise be called from whatever thread invoked launch().
            // The dispatcher is constructor-tunable so tests can substitute a
            // TestDispatcher and keep this synchronous under virtual time.
            withContext(uiDispatcher) {
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
            // compareAndSet — atomic check-and-clear. Prevents a TOCTOU race where
            // markComplete() (or a new launch attempt) clears/reasserts the flag
            // between a non-atomic get() and set().
            if (isPurchaseFlowInProgress.compareAndSet(true, false)) {
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
    public data object Success : PurchaseFlowResult()
    public data object AlreadyInProgress : PurchaseFlowResult()
    public data object InvalidActivityState : PurchaseFlowResult()
    public data object BillingUnavailable : PurchaseFlowResult()
    public data class Error(val cause: Throwable) : PurchaseFlowResult()
}
