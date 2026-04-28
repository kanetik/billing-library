package com.kanetik.billing

import com.android.billingclient.api.BillingClient
import com.kanetik.billing.exception.BillingException

/**
 * Internal: the library's view of the underlying Play Billing connection,
 * carrying the live [BillingClient] for in-library calls. Mapped to the public
 * [BillingConnectionResult] before being exposed via
 * [BillingConnector.connectToBilling].
 */
internal sealed class InternalConnectionState {
    internal data class Connected(val client: BillingClient) : InternalConnectionState()
    internal data class Failed(val exception: BillingException) : InternalConnectionState()
}
