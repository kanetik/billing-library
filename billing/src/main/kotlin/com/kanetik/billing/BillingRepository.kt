package com.kanetik.billing

/**
 * The full Kanetik Billing surface: querying products / launching flows /
 * acknowledging / consuming ([BillingActions]), observing purchase updates
 * ([BillingPurchaseUpdatesOwner]), and managing the underlying Play Billing
 * connection ([BillingConnector]).
 *
 * Most consumers inject the full [BillingRepository] in one place and depend
 * on the narrower interface(s) they actually use everywhere else. Obtain an
 * instance via [BillingRepositoryCreator.create].
 */
public interface BillingRepository : BillingActions, BillingPurchaseUpdatesOwner, BillingConnector {
    /**
     * Push a synthetic [PurchaseRevoked] event into
     * [observePurchaseUpdates][BillingPurchaseUpdatesOwner.observePurchaseUpdates].
     * The library is transport-agnostic — the consumer is responsible for
     * decoding RTDN / FCM / polling / deeplink signals into a
     * `(purchaseToken, reason)` pair and calling this method.
     *
     * Routed through a dedicated replay-cache channel (`replay = 16`) so up
     * to 16 revocations arriving before a subscriber attaches survive the
     * gap — the typical consumer pattern (FCM listener decodes the RTDN
     * payload at process start, the UI collector attaches a moment later)
     * needs this. The buffer absorbs realistic FCM bursts (multi-product
     * chargebacks resolving simultaneously, etc.); for guaranteed delivery
     * of every event past the bound, persist on the consumer side before
     * calling this method.
     *
     * Suspending: `emit` semantics, not `tryEmit` — the call suspends if the
     * underlying buffer is full rather than silently dropping the event.
     * Buffer is sized to absorb bursty traffic in practice; suspension is
     * extremely rare.
     *
     * @param purchaseToken the Play Billing purchase token of the revoked
     *   purchase. The library does not validate the token shape; consumers
     *   should pass through whatever Play sent in the originating
     *   notification.
     * @param reason a [RevocationReason] bucket; pick the most specific
     *   value that applies, or [RevocationReason.Other] if none fit.
     */
    public suspend fun emitExternalRevocation(purchaseToken: String, reason: RevocationReason)
}
