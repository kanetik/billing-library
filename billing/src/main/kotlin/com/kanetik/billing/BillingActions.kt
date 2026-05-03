package com.kanetik.billing

import android.app.Activity
import androidx.annotation.AnyThread
import androidx.annotation.CheckResult
import androidx.annotation.MainThread
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryPurchasesParams
import com.kanetik.billing.exception.BillingException

/**
 * Suspend-style operations against Google Play Billing.
 *
 * Every method waits for the underlying [com.android.billingclient.api.BillingClient]
 * connection (see [BillingConnector]), runs with internal retry / backoff for transient
 * failures, and surfaces hard failures as a typed [BillingException] subtype so
 * consumers can branch by [com.kanetik.billing.RetryType] without parsing strings.
 */
public interface BillingActions {

    /**
     * @return true if the underlying [BillingClient] reports the [feature] is supported
     * on the current device + Play Store install.
     */
    @AnyThread
    public suspend fun isFeatureSupported(@BillingClient.FeatureType feature: String): Boolean

    /**
     * Returns the user's currently-owned purchases for the [params]-specified product type.
     * Pass [BillingClient.ProductType.INAPP] for one-time products,
     * [BillingClient.ProductType.SUBS] for subscriptions.
     */
    @AnyThread
    public suspend fun queryPurchases(params: QueryPurchasesParams): List<Purchase>

    /**
     * Resolves [params] to product details. Products that Play could not fetch (typo'd
     * IDs, geo-restricted, etc.) are silently dropped from the returned list — use
     * [queryProductDetailsWithUnfetched] to diagnose them.
     */
    @AnyThread
    public suspend fun queryProductDetails(params: QueryProductDetailsParams): List<ProductDetails>

    /**
     * Same as [queryProductDetails] but also exposes the list of products Play Billing
     * could not fetch. The Kotlin coroutine extension shipped by `billing-ktx` 8.x
     * returns the legacy `ProductDetailsResult`, which discards this information — use
     * this overload when you need diagnostics on missing products.
     */
    @AnyThread
    public suspend fun queryProductDetailsWithUnfetched(params: QueryProductDetailsParams): ProductDetailsQuery

    /**
     * Consumes a one-time consumable purchase, allowing the user to buy it again.
     * @return the consumed purchase token. Always present on a successful consume —
     *   PBL guarantees this. The library throws a [BillingException] subtype if the
     *   underlying call fails, so this method never returns under failure.
     */
    @AnyThread
    public suspend fun consumePurchase(params: ConsumeParams): String

    /**
     * Convenience overload that consumes [purchase] using its purchase token.
     *
     * Symmetry with [acknowledgePurchase]`(Purchase)`. Builds [ConsumeParams] from the
     * purchase's token and delegates. Use the [params][consumePurchase] overload directly
     * if you need to pass additional params (rare).
     */
    @AnyThread
    public suspend fun consumePurchase(purchase: Purchase): String {
        val params = ConsumeParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        return consumePurchase(params)
    }

    /**
     * Acknowledges a non-consumable purchase. Play requires acknowledgement within
     * 3 days of purchase or the transaction is auto-refunded.
     */
    @AnyThread
    public suspend fun acknowledgePurchase(params: AcknowledgePurchaseParams)

    /**
     * Convenience overload that acknowledges [purchase] only if it isn't already
     * acknowledged.
     *
     * Calling [acknowledgePurchase] on an already-acknowledged purchase produces
     * a [BillingException.DeveloperErrorException][com.kanetik.billing.exception.BillingException.DeveloperErrorException]
     * from Play. Google's integration guide explicitly recommends checking
     * [Purchase.isAcknowledged] first; this overload bakes that check in so
     * callers don't have to remember.
     *
     * Builds [AcknowledgePurchaseParams] from [purchase]'s purchase token and
     * delegates to the params-based [acknowledgePurchase]. No-ops silently if
     * [purchase] is already acknowledged.
     */
    @AnyThread
    public suspend fun acknowledgePurchase(purchase: Purchase) {
        if (purchase.isAcknowledged) return
        val params = AcknowledgePurchaseParams.newBuilder()
            .setPurchaseToken(purchase.purchaseToken)
            .build()
        acknowledgePurchase(params)
    }

    /**
     * High-level helper: post-process a [purchase] by either consuming it (for
     * consumables) or acknowledging it (for non-consumables), based on [consume].
     * Returns a typed [HandlePurchaseResult] — branch on it; **don't ignore
     * the return value**.
     *
     * ## Why a typed result, not a thrown exception
     *
     * Granting entitlement on a failed acknowledge is the most common Play
     * Billing bug: caller writes `runCatching { handlePurchase(...) }` and
     * `grantPremium()` outside the `.onSuccess { }` branch, Play auto-refunds
     * the unacknowledged purchase within ~3 days, and the user's premium
     * silently evaporates. Returning [HandlePurchaseResult] makes the failure
     * case a sealed-type variant the compiler nudges callers to handle:
     *
     * ```
     * when (val r = billing.handlePurchase(purchase, consume = false)) {
     *     HandlePurchaseResult.Success -> grantPremium()
     *     HandlePurchaseResult.NotPurchased -> {} // pending — wait
     *     is HandlePurchaseResult.Failure -> showError(r.exception.userFacingCategory)
     * }
     * ```
     *
     * The auto-recovery sweep ([com.kanetik.billing.PurchasesUpdate.Recovered])
     * re-emits the unacknowledged purchase on the next successful connection,
     * so a transient [HandlePurchaseResult.Failure] is recoverable; a
     * granted-then-refunded purchase is not. **This recovery is conditional
     * on [com.kanetik.billing.BillingRepositoryCreator.create]'s
     * `recoverPurchasesOnConnect` parameter being left at its default (`true`)** —
     * consumers that opt out are responsible for their own retry / reconciliation
     * path (see [HandlePurchaseResult.Failure]).
     *
     * Lower-level [consumePurchase] / [acknowledgePurchase] still throw
     * [com.kanetik.billing.exception.BillingException] directly — callers at
     * that layer are already in the weeds and a thrown exception is
     * appropriate.
     *
     * ## Behavior contract
     *
     * Bakes in three things consumers shouldn't have to remember:
     *  1. Only act on [Purchase.PurchaseState.PURCHASED] — pending and canceled
     *     purchases must wait for their terminal state. Calling this on a non-
     *     PURCHASED purchase returns [HandlePurchaseResult.NotPurchased].
     *  2. For [consume] = false, the [acknowledgePurchase]`(Purchase)` overload's
     *     `isAcknowledged` short-circuit applies (already-acked → Success).
     *  3. For [consume] = true, the underlying consume call is the one that also
     *     satisfies Play's acknowledgement requirement (Play treats consume as
     *     implicit acknowledgement for consumables).
     *
     * If you need the consumed token specifically (for server reporting),
     * call [consumePurchase]`(Purchase)` directly instead — it still throws
     * on failure and returns the token on success.
     *
     * ## Multi-quantity consumables
     *
     * This method handles the *acknowledgement* side correctly for any quantity
     * — Play's consume API consumes the entire purchase regardless of unit count.
     * But your *entitlement-grant* code must read [Purchase.getQuantity] and
     * grant `quantity` units, not 1. The field defaults to 1 (so single-unit
     * code keeps working), and Play supports multi-quantity for consumables
     * configured in the Play Console. Ignoring the field on a multi-quantity
     * purchase silently under-grants.
     *
     * @param purchase The purchase to handle.
     * @param consume `true` to consume (for consumables); `false` to acknowledge
     *   (for non-consumables).
     * @return [HandlePurchaseResult.Success] if the call landed,
     *   [HandlePurchaseResult.NotPurchased] if the purchase wasn't in PURCHASED
     *   state, or [HandlePurchaseResult.Failure] (carrying the underlying
     *   `BillingException`) if the acknowledge / consume call failed after the
     *   library's retry budget.
     */
    @AnyThread
    @CheckResult(suggest = "branch on HandlePurchaseResult.Success / Failure to gate entitlement grant — ignoring this return value re-introduces the grant-on-failure bug the typed result is meant to prevent")
    public suspend fun handlePurchase(purchase: Purchase, consume: Boolean): HandlePurchaseResult {
        if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) {
            return HandlePurchaseResult.NotPurchased
        }
        return try {
            if (consume) {
                consumePurchase(purchase)
            } else {
                acknowledgePurchase(purchase)
            }
            HandlePurchaseResult.Success
        } catch (ce: kotlinx.coroutines.CancellationException) {
            // Always rethrow — structured cancellation must propagate.
            throw ce
        } catch (e: BillingException) {
            HandlePurchaseResult.Failure(e)
        } catch (e: Exception) {
            // Custom BillingActions implementations might throw something other
            // than BillingException (a defensive NPE from a `!!` contract check,
            // an IllegalStateException from a fake, etc.). Honor the typed-result
            // contract by wrapping into Failure(UnknownException) rather than
            // letting non-BillingException throwables escape.
            HandlePurchaseResult.Failure(
                BillingException.UnknownException(
                    com.android.billingclient.api.BillingResult.newBuilder()
                        .setResponseCode(com.android.billingclient.api.BillingClient.BillingResponseCode.ERROR)
                        .setDebugMessage("handlePurchase wrapped non-BillingException: ${e::class.simpleName}: ${e.message}")
                        .build()
                )
            )
        }
    }

    /**
     * Launches the Play Billing purchase flow.
     *
     * Must be called on the main thread. The returned coroutine completes once Play
     * has shown the purchase UI; the actual purchase outcome arrives separately via
     * [BillingPurchaseUpdatesOwner.observePurchaseUpdates] (and may take seconds to
     * minutes — pending purchases can sit unresolved indefinitely).
     *
     * For one-time products, prefer [com.kanetik.billing.ext.toOneTimeFlowParams] to
     * build [params] correctly under PBL 8's offer-token rules. For higher-level
     * orchestration (in-flight guard, watchdog, typed result), see
     * [com.kanetik.billing.ext.PurchaseFlowCoordinator].
     */
    @MainThread
    public suspend fun launchFlow(activity: Activity, params: BillingFlowParams)

    /**
     * Shows Google Play's transactional in-app messages overlaid on [activity].
     *
     * Useful for prompting the user to fix a failed payment method on a
     * subscription, etc. Play decides whether there's a message to show and
     * which UI to render; the result indicates what (if anything) the user did.
     *
     * Build [params] from [InAppMessageParams.newBuilder] with the relevant
     * `addInAppMessageCategoryToShow(...)` categories (typically
     * [InAppMessageParams.InAppMessageCategoryId.TRANSACTIONAL]).
     *
     * Must be called on the main thread (PBL renders dialogs from the calling
     * activity's window).
     *
     * @return [BillingInAppMessageResult.NoActionNeeded] if Play had nothing to
     *   show or the user took no action; [BillingInAppMessageResult.SubscriptionStatusUpdated]
     *   with the affected purchase token if the user fixed something. Throws a
     *   [BillingException] subtype if Play rejects the call (service disconnected,
     *   activity invalid, etc.).
     */
    @MainThread
    public suspend fun showInAppMessages(
        activity: Activity,
        params: InAppMessageParams
    ): BillingInAppMessageResult
}
