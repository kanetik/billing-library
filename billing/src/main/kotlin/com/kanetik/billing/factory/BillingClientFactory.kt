package com.kanetik.billing.factory

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PurchasesUpdatedListener

interface BillingClientFactory {
    fun createBillingClient(context: Context, listener: PurchasesUpdatedListener): BillingClient
}