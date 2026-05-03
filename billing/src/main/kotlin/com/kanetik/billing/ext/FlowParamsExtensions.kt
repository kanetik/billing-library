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
 * alternative price tiers). The default [offerSelector] picks the first available
 * offer, which is correct for the dominant case where a product has a single
 * auto-migrated offer entry.
 *
 * For products configured with multiple offers, override [offerSelector] to pick
 * the right one — e.g. matching by `offerId`, picking a pre-order over a
 * standard purchase, or applying region-specific logic:
 *
 * ```
 * productDetails.toOneTimeFlowParams(
 *     offerSelector = { offers ->
 *         offers.firstOrNull { it.offerId == "spring-promo" }
 *             ?: offers.firstOrNull()
 *     },
 *     obfuscatedAccountId = userId
 * )
 * ```
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
 *
 * ## Source-compat trade-off
 *
 * Adding [obfuscatedProfileId] necessarily breaks one of two pre-existing
 * Kotlin call patterns. We chose to break the rarer one:
 *  - **Trailing-lambda preserved** — `product.toOneTimeFlowParams { selector }`
 *    and `product.toOneTimeFlowParams(accountId) { selector }` continue to
 *    compile; the trailing lambda binds to the still-last [offerSelector].
 *  - **Positional 2-arg `(accountId, selector)` broken** — calls like
 *    `product.toOneTimeFlowParams("user-id", customSelector)` now bind the
 *    second argument to [obfuscatedProfileId] (a `String?`), producing a
 *    type-mismatch compile error. Migration: switch to named args
 *    (`obfuscatedAccountId = ..., offerSelector = ...`) — the canonical
 *    style this KDoc has always recommended.
 *
 * Trailing-lambda is the idiomatic Kotlin pattern for callbacks; preserving
 * it was the higher priority. Java callers see the new parameter as required
 * (no `@JvmOverloads` bridge); pass `null` explicitly or rebuild.
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
