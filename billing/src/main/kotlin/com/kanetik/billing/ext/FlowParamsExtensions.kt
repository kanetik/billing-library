package com.kanetik.billing.ext

import com.android.billingclient.api.BillingFlowParams
import com.android.billingclient.api.ProductDetails

/**
 * Builds a [BillingFlowParams] for launching a one-time-product purchase flow.
 *
 * PBL 8 restructured one-time products to expose `oneTimePurchaseOfferDetailsList`
 * — even consumable / non-consumable products that historically had no offer concept
 * now ship with one auto-migrated entry that carries an `offerToken`. **You must
 * pass that `offerToken` to `setOfferToken(...)` when present**, or `launchBillingFlow`
 * fails with `DEVELOPER_ERROR`. This is the #1 footgun developers hit when upgrading
 * from PBL 7.x → 8.x.
 *
 * Picks an offer via [offerSelector] and sets its `offerToken` on the flow params.
 * If no offer list is present (very old PBL pre-8 artifacts) or the selector
 * returns `null`, falls back to no offer token — keeping things working for any
 * hypothetical product that wasn't migrated.
 *
 * For subscriptions, use the dedicated subscription flow params — this helper is
 * one-time-product only. (Subscription helpers are planned for v0.2.0; until then,
 * build subscription flow params directly via `BillingFlowParams.newBuilder()`.)
 *
 * ## Offer selection
 *
 * PBL 8.0+ supports one-time products with **multiple offers** (e.g. pre-orders,
 * alternative price tiers, rentals). The default [offerSelector] picks the first
 * available offer, which is correct for the dominant case where a product has a
 * single auto-migrated offer entry.
 *
 * For products configured with multiple offers, override [offerSelector] to pick
 * the right one — e.g. matching by `offerId`, picking a pre-order over a standard
 * purchase via [isPreorder], or applying region-specific logic:
 *
 * ```
 * productDetails.toOneTimeFlowParams(
 *     offerSelector = { offers ->
 *         offers.firstOrNull { it.isPreorder }
 *             ?: offers.firstOrNull()
 *     },
 *     obfuscatedAccountId = userId,
 * )
 * ```
 *
 * ## Multi-quantity (Play Console + `purchase.quantity`)
 *
 * Multi-quantity for consumables is configured per-product in Play Console
 * (*Multi-quantity purchases* flag) and rendered by Play's own purchase dialog
 * — there's no `setPurchaseQuantity` knob on the PBL 9 client. The dialog lets
 * the user pick the unit count; the resulting [com.android.billingclient.api.Purchase]
 * carries `purchase.quantity` (defaults to 1). Your entitlement-grant code must
 * read that field and grant `quantity` units, not 1. The library handles
 * acknowledgement correctly for any quantity (Play's consume API consumes the
 * whole purchase regardless), so only your wallet-ledger code needs the
 * awareness.
 *
 * See the [Consumables ledger guide](https://kanetik.github.io/billing-library/guides/consumables/)
 * for the wallet-on-the-side pattern that pairs with multi-quantity consumables.
 *
 * @receiver The [ProductDetails] for a one-time product (queried via
 *   [com.kanetik.billing.BillingActions.queryProductDetails]).
 * @param obfuscatedAccountId Optional stable per-install identifier passed to Play
 *   for fraud-detection correlation. Should be a stable opaque ID (UUID is fine);
 *   must contain no PII per Google's policy. Leave null to omit.
 * @param obfuscatedProfileId Optional secondary opaque ID for apps with multiple
 *   user profiles per install (e.g. family-sharing scenarios). Same PII rules as
 *   [obfuscatedAccountId]. Leave null to omit. Most apps will only need
 *   [obfuscatedAccountId].
 * @param offerSelector Strategy for picking which one-time-purchase offer's token
 *   to set on the flow params. Receives the full list of offers Play returned for
 *   the product; returns the chosen offer (or `null` to omit `setOfferToken`).
 *   Defaults to picking the first available offer.
 */
public fun ProductDetails.toOneTimeFlowParams(
    obfuscatedAccountId: String? = null,
    obfuscatedProfileId: String? = null,
    offerSelector: (List<ProductDetails.OneTimePurchaseOfferDetails>) -> ProductDetails.OneTimePurchaseOfferDetails? = { it.firstOrNull() }
): BillingFlowParams {
    val offerToken = oneTimePurchaseOfferDetailsList?.let(offerSelector)?.offerToken
    val productDetailsParamsBuilder = BillingFlowParams.ProductDetailsParams
        .newBuilder()
        .setProductDetails(this)
    if (offerToken != null) {
        productDetailsParamsBuilder.setOfferToken(offerToken)
    }

    val builder = BillingFlowParams.newBuilder()
        .setProductDetailsParamsList(listOf(productDetailsParamsBuilder.build()))

    if (!obfuscatedAccountId.isNullOrBlank()) {
        builder.setObfuscatedAccountId(obfuscatedAccountId)
    }

    if (!obfuscatedProfileId.isNullOrBlank()) {
        builder.setObfuscatedProfileId(obfuscatedProfileId)
    }

    return builder.build()
}

/**
 * Returns `true` if this one-time-product offer is a pre-order — Play hasn't
 * fulfilled it yet, and the user is signing up to be charged on a future release
 * date. PBL 8.1+ exposes pre-order metadata via `getPreorderDetails()`; this
 * extension is sugar over the null-check.
 *
 * Pre-order offers have a few wrinkles worth knowing:
 *
 *  - The purchase shows up as [com.kanetik.billing.FlowOutcome.Pending] until
 *    Play fulfills it on the release date — **do not grant entitlement** on
 *    that pending event. Wait for the matching
 *    [com.kanetik.billing.OwnedPurchases.Live] update that arrives when the
 *    fulfillment lands.
 *  - The user can cancel a pending pre-order from the Play Store before the
 *    release date; Play surfaces the cancellation via Voided Purchases API
 *    server-side (no client event today). Apps that pre-grant feature access
 *    on pre-order signup should reconcile against the Voided Purchases API
 *    via their backend before treating the entitlement as durable.
 *  - One product can carry both pre-order and standard offers
 *    simultaneously. Pass an [offerSelector] to `toOneTimeFlowParams` that
 *    branches on [isPreorder] to drive the user into the right one.
 *
 * Example:
 *
 * ```kotlin
 * val params = productDetails.toOneTimeFlowParams(
 *     offerSelector = { offers ->
 *         val preorder = offers.firstOrNull { it.isPreorder }
 *         val regular = offers.firstOrNull { !it.isPreorder }
 *         if (userOptedIntoPreorder) preorder ?: regular else regular ?: preorder
 *     }
 * )
 * ```
 */
public val ProductDetails.OneTimePurchaseOfferDetails.isPreorder: Boolean
    get() = preorderDetails != null
