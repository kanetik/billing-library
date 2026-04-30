package com.kanetik.billing

import com.android.billingclient.api.Purchase
import io.mockk.every
import io.mockk.mockk

/**
 * Test fixtures for [Purchase] mocks.
 *
 * Purchase has a public `(json, signature)` constructor, but Purchase parses the
 * JSON via `org.json.JSONObject`, which is an Android-stubbed class in pure-JVM
 * tests (NPE'd by AGP's "not mocked" stub). Mocking Purchase directly via mockk
 * sidesteps that.
 */
internal fun fakePurchase(
    productId: String = "test_product",
    purchaseToken: String = "test_token",
    purchaseState: Int = Purchase.PurchaseState.PURCHASED,
    isAcknowledged: Boolean = false,
    products: List<String> = listOf(productId)
): Purchase = mockk<Purchase>(relaxed = true).also {
    every { it.purchaseToken } returns purchaseToken
    every { it.purchaseState } returns purchaseState
    every { it.isAcknowledged } returns isAcknowledged
    every { it.products } returns products
}
