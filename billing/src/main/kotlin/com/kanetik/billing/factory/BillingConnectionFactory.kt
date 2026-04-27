package com.kanetik.billing.factory

import com.android.billingclient.api.PurchasesUpdatedListener
import com.kanetik.billing.BillingConnectionResult
import kotlinx.coroutines.flow.Flow

/**
 * Strategy for turning a [BillingClient][com.android.billingclient.api.BillingClient]
 * lifecycle into a coroutine [Flow] of [BillingConnectionResult]s.
 *
 * The default implementation uses [kotlinx.coroutines.flow.callbackFlow] to bridge
 * Play's [com.android.billingclient.api.BillingClientStateListener] callbacks. Replace
 * it only if you need a custom connection model (e.g. a fake for testing).
 */
public interface BillingConnectionFactory {

    public fun createBillingConnectionFlow(
        listener: PurchasesUpdatedListener
    ): Flow<BillingConnectionResult>
}
