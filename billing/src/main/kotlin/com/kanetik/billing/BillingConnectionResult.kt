package com.kanetik.billing

import com.android.billingclient.api.BillingClient
import com.kanetik.billing.exception.BillingException

sealed class BillingConnectionResult {
    data class Success(val client: BillingClient) : BillingConnectionResult()
    data class Error(val exception: BillingException) : BillingConnectionResult()
}