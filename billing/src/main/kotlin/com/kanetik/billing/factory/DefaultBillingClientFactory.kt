package com.kanetik.billing.factory

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener

/**
 * The default [BillingClientFactory] used by [BillingRepositoryCreator][com.kanetik.billing.BillingRepositoryCreator]
 * when no override is supplied.
 *
 * Builds a [BillingClient] with the PBL 8.x recommended setup:
 *  - `enablePendingPurchases` with `enableOneTimeProducts` (replaces the no-arg
 *    overload removed in PBL 8.0.0)
 *  - `enableAutoServiceReconnection` so PBL handles reconnects internally
 *  - the supplied [PurchasesUpdatedListener]
 *
 * Subclass or implement [BillingClientFactory] yourself if you need to customize
 * the builder (e.g. add `enableUserChoiceBilling`, swap configuration for tests).
 */
public class DefaultBillingClientFactory : BillingClientFactory {

    override fun createBillingClient(
        context: Context,
        listener: PurchasesUpdatedListener
    ): BillingClient = BillingClient
        .newBuilder(context)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .setListener(listener)
        .build()
}
