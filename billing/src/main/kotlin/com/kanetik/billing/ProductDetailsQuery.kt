package com.kanetik.billing

import com.android.billingclient.api.ProductDetails
import com.android.billingclient.api.UnfetchedProduct

/**
 * Combined result of [BillingActions.queryProductDetailsWithUnfetched] —
 * fetched [productDetails] paired with the [unfetchedProducts] Play Billing
 * couldn't resolve (typo'd product IDs, geo-restricted products, etc.).
 *
 * Wraps PBL's `QueryProductDetailsResult` so the library's public API doesn't
 * pin its ABI to PBL's holder-class shape. The list element types
 * ([ProductDetails], [UnfetchedProduct]) are still PBL types — we expose those
 * elsewhere ([BillingActions.queryProductDetails]) too.
 */
public data class ProductDetailsQuery(
    public val productDetails: List<ProductDetails>,
    public val unfetchedProducts: List<UnfetchedProduct>
)
