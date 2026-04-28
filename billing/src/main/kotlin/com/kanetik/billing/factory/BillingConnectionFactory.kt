package com.kanetik.billing.factory

import com.android.billingclient.api.PurchasesUpdatedListener
import com.kanetik.billing.InternalConnectionState
import kotlinx.coroutines.flow.Flow

/**
 * Internal strategy for turning a
 * [BillingClient][com.android.billingclient.api.BillingClient] lifecycle into a
 * coroutine [Flow] of [InternalConnectionState]. The library uses
 * [CoroutinesBillingConnectionFactory] as the only impl in v0.1.0; the
 * interface exists so the upcoming `:billing-testing` artifact (v0.2.0) can
 * substitute a fake.
 *
 * Consumers do not implement this. To customize the underlying
 * [com.android.billingclient.api.BillingClient], provide a [BillingClientFactory]
 * to [BillingRepositoryCreator.create][com.kanetik.billing.BillingRepositoryCreator.create].
 */
internal interface BillingConnectionFactory {

    fun createBillingConnectionFlow(
        listener: PurchasesUpdatedListener
    ): Flow<InternalConnectionState>
}
