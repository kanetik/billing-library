package com.kanetik.billing

import com.kanetik.billing.exception.BillingException

/**
 * Outcome of a Play Billing connection attempt, emitted by
 * [BillingConnector.connectToBilling].
 *
 * Treat [Success] as an opaque "connection up" signal — the library handles the
 * underlying [com.android.billingclient.api.BillingClient] internally. To act
 * on a successful connection, call methods on
 * [BillingActions][com.kanetik.billing.BillingActions]; they wait for the
 * connection automatically.
 */
public sealed class BillingConnectionResult {
    /** Connection established. Subsequent [BillingActions] calls are ready to fire. */
    public data object Success : BillingConnectionResult()

    /** Connection failed or dropped. The cause is wrapped in a typed [BillingException]. */
    public data class Error(public val exception: BillingException) : BillingConnectionResult()
}
