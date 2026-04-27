package com.kanetik.billing

import kotlinx.coroutines.flow.Flow

interface BillingPurchaseUpdatesOwner {

    fun observePurchaseUpdates(): Flow<PurchasesUpdate>
}