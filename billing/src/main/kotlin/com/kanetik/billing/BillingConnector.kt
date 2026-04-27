package com.kanetik.billing

import kotlinx.coroutines.flow.Flow

interface BillingConnector {

    fun connectToBilling(): Flow<BillingConnectionResult>
}