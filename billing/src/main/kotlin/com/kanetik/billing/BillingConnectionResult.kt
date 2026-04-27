package com.kanetik.billing

import com.android.billingclient.api.BillingClient
import com.kanetik.billing.exception.BillingException

/**
 * Outcome of a Play Billing connection attempt, emitted by
 * [BillingConnector.connectToBilling].
 */
public sealed class BillingConnectionResult {
    /** Connection established. The [client] is ready for use until the next disconnect. */
    public data class Success(val client: BillingClient) : BillingConnectionResult()

    /** Connection failed or dropped. The cause is wrapped in a typed [BillingException]. */
    public data class Error(val exception: BillingException) : BillingConnectionResult()
}
