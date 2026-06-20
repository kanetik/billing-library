package com.kanetik.billing

import com.kanetik.billing.choice.BillingChoiceActions

/**
 * The full Kanetik Billing surface: querying products / launching flows /
 * acknowledging / consuming ([BillingActions]), observing purchase updates
 * ([BillingPurchaseUpdatesOwner]), managing the underlying Play Billing
 * connection ([BillingConnector]), and the experimental Billing Choice surface
 * ([BillingChoiceActions]).
 *
 * Most consumers inject the full [BillingRepository] in one place and depend
 * on the narrower interface(s) they actually use everywhere else. Obtain an
 * instance via [BillingRepositoryCreator.create].
 *
 * The [BillingChoiceActions] methods are gated behind
 * [ExperimentalBillingChoiceApi][com.kanetik.billing.choice.ExperimentalBillingChoiceApi];
 * inheriting the interface here does not force opt-in on consumers — only
 * *calling* a Billing Choice method does.
 *
 * **Implementers, note:** adding [BillingChoiceActions] as a supertype is a
 * source-breaking change for anyone who implements [BillingRepository]
 * directly (most commonly a hand-rolled test fake) — those implementations
 * must now also provide `isBillingChoiceAvailable`, `getBillingChoiceInfo`,
 * and `showBillingProgramInformationDialog`, or delegate them to a real
 * repository. The vast majority of consumers obtain the repository from
 * [BillingRepositoryCreator.create] and are unaffected. (A
 * `FakeBillingRepository` test artifact that absorbs additions like this is
 * planned for v0.2.0 — see the roadmap.)
 */
public interface BillingRepository :
    BillingActions,
    BillingPurchaseUpdatesOwner,
    BillingConnector,
    BillingChoiceActions {
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
