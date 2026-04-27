package com.kanetik.billing.factory

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener

internal class DefaultBillingClientFactory : BillingClientFactory {

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
