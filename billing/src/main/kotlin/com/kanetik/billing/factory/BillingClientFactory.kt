package com.kanetik.billing.factory

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PurchasesUpdatedListener

/**
 * Strategy for constructing a [BillingClient].
 *
 * Most consumers don't need to provide their own — the default impl builds a
 * client with Play Billing 8.x's recommended setup
 * (`enablePendingPurchases(enableOneTimeProducts())`, `enableAutoServiceReconnection()`).
 *
 * Implement this if you need to:
 *  - Stub the client for unit tests (until the dedicated `:billing-testing` artifact
 *    arrives in v0.2.0).
 *  - Toggle non-default builder options for a specific app's needs.
 */
public interface BillingClientFactory {
    public fun createBillingClient(context: Context, listener: PurchasesUpdatedListener): BillingClient
}
