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
 * This extension picks the first available offer detail's token (the auto-migrated
 * one for legacy products) and sets it. If no list is present (very old PBL pre-8
 * artifacts), it falls back to no offer token — keeping things working for any
 * hypothetical product that wasn't migrated.
 *
 * For subscriptions, use the dedicated subscription flow params — this helper is
 * one-time-product only. (Subscription helpers are planned for v0.2.0; until then,
 * build subscription flow params directly via `BillingFlowParams.newBuilder()`.)
 *
 * @receiver The [ProductDetails] for a one-time product (queried via
 *   [com.kanetik.billing.BillingActions.queryProductDetails]).
 * @param obfuscatedAccountId Optional stable per-install identifier passed to Play
 *   for fraud-detection correlation. Should be a stable opaque ID (UUID is fine);
 *   must contain no PII per Google's policy. Leave null to omit.
 */
public fun ProductDetails.toOneTimeFlowParams(
    obfuscatedAccountId: String? = null
): BillingFlowParams {
    val offerToken = oneTimePurchaseOfferDetailsList?.firstOrNull()?.offerToken
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

    return builder.build()
}
