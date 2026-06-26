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

    /**
     * Deterministic verdict on whether Play Billing can be used on this device,
     * disambiguating a *terminal* "this device can't pay" state from a merely
     * *transient* failure — see [BillingAvailability] for why the raw
     * `BILLING_UNAVAILABLE` response code can't be trusted for that distinction.
     *
     * Resolution order:
     *  1. If the Play Store package is absent or disabled (an offline, stable
     *     fact) → [BillingAvailability.UNAVAILABLE].
     *  2. Otherwise attempt a connection (bounded by an internal timeout):
     *     success → [BillingAvailability.AVAILABLE]; any failure, including a
     *     transient code 3 → [BillingAvailability.UNKNOWN].
     *
     * Use this to gate irreversible decisions (granting a free fallback tier,
     * permanently hiding a purchase CTA): act on [BillingAvailability.UNAVAILABLE]
     * only, and keep [BillingAvailability.UNKNOWN] users in a neutral/retry state.
     *
     * Suspends until the determination is made. Safe to call from any thread.
     *
     * **Implementers, note:** this is a defaulted interface method returning
     * [BillingAvailability.UNKNOWN] so existing hand-rolled fakes keep compiling;
     * the real [BillingRepository] from [BillingRepositoryCreator.create]
     * overrides it with the full resolution above.
     */
    public suspend fun queryBillingAvailability(): BillingAvailability =
        BillingAvailability.UNKNOWN
}
