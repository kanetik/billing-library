package com.kanetik.billing.factory

import com.android.billingclient.api.PurchasesUpdatedListener
import com.kanetik.billing.BillingConnectionResult
import kotlinx.coroutines.flow.Flow

interface BillingConnectionFactory {

    fun createBillingConnectionFlow(
        listener: PurchasesUpdatedListener
    ): Flow<BillingConnectionResult>

}