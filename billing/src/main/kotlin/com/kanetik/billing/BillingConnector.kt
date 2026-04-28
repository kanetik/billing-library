package com.kanetik.billing

import kotlinx.coroutines.flow.SharedFlow

/**
 * Owner of the underlying Play Billing connection.
 *
 * Most consumers don't call [connectToBilling] directly — every [BillingActions]
 * method already lazily awaits a connection. The flow is exposed for advanced cases:
 * holding the connection warm while a lifecycle is started (see
 * [com.kanetik.billing.lifecycle.BillingConnectionLifecycleManager]), or surfacing
 * connection-level errors to the UI layer.
 *
 * Returned as [SharedFlow] to communicate that this is a hot, shared stream — multiple
 * collectors share one underlying connection. Successful connection emits
 * [BillingConnectionResult.Success]; transient or fatal connection errors emit
 * [BillingConnectionResult.Error] with a typed
 * [com.kanetik.billing.exception.BillingException].
 */
public interface BillingConnector {

    public fun connectToBilling(): SharedFlow<BillingConnectionResult>
}
