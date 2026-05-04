package com.kanetik.billing.entitlement

/**
 * The cached entitlement state surfaced by [EntitlementCache].
 *
 * Three terminal states cover the full lifecycle of an "is the user entitled
 * to premium right now?" question:
 *
 *  - [Granted] — the cache has confirmed the user owns a matching purchase.
 *    Show premium UI.
 *  - [InGrace] — the cache recently saw entitlement, then hit a transient
 *    failure (network outage, billing service unavailable, etc.) before it
 *    could re-confirm. The user is still treated as entitled until
 *    [InGrace.expiresAtMs] passes; after that the cache transitions to
 *    [Revoked]. Lets premium features keep working through a brief outage
 *    instead of yanking them out from under a paid user.
 *  - [Revoked] — the cache has either never seen entitlement, or grace has
 *    expired without a successful re-check. Hide premium UI.
 *
 * Branch on the sealed type to render UI rather than comparing instances by
 * equality. The [InGrace.expiresAtMs] timestamp is set once when the
 * transition into grace happens (anchored to the last confirmed observation
 * + the policy window) and stays stable until grace expires; the cache
 * doesn't emit a new `InGrace(expiresAtMs = ...)` on every tick. So
 * `state.distinctUntilChanged()` works as expected; a fresh `Failure` with
 * the same reason produces the same expiresAt and gets de-duped.
 */
public sealed interface EntitlementState {

    /**
     * The user owns a matching purchase. Show premium UI.
     *
     * Reached on:
     *  - A [com.kanetik.billing.OwnedPurchases.Live] containing a purchase
     *    matching the cache's `productPredicate`.
     *  - A [com.kanetik.billing.OwnedPurchases.Recovered] containing a
     *    matching purchase (the recovery sweep on connect).
     *  - A persisted [EntitlementSnapshot] read at start with `isEntitled = true`.
     */
    public data object Granted : EntitlementState

    /**
     * Entitlement was recently confirmed but a subsequent
     * [com.kanetik.billing.FlowOutcome.Failure] prevented re-confirmation.
     * Treat the user as entitled until [expiresAtMs]; after that the cache
     * transitions to [Revoked].
     *
     * @property expiresAtMs Wall-clock time (in `System.currentTimeMillis()`
     *   units, or whatever the cache's injected `clock` returns) at which
     *   grace ends. Compare against the same clock when persisting / restoring.
     * @property reason Why the cache is in grace — drives the policy window
     *   and helps consumers log / analyze outage causes.
     */
    public data class InGrace(
        public val expiresAtMs: Long,
        public val reason: GraceReason,
    ) : EntitlementState

    /**
     * No entitlement. Hide premium UI.
     *
     * Reached on:
     *  - A [com.kanetik.billing.PurchaseRevoked] event whose `purchaseToken`
     *    matches the cached snapshot's last confirmed purchase. The consumer
     *    pushes these via `emitExternalRevocation` from their RTDN→FCM (or
     *    polling, or deeplink) pipeline.
     *  - An [InGrace] state whose [InGrace.expiresAtMs] has elapsed without
     *    a fresh `Granted` confirmation.
     *  - The default state when no prior snapshot exists and nothing has
     *    arrived yet.
     *
     * Notably **not** reached on a non-matching `OwnedPurchases.Recovered`
     * (or `Live`) event. Recovered only emits the unacknowledged subset of
     * Play-side owned purchases, so an empty Recovered for an entitled user
     * with an already-acknowledged purchase doesn't mean Play revoked
     * anything — it just means there's nothing left to acknowledge. The
     * cache treats Recovered/Live as grant-only signals to avoid that
     * false-revocation footgun.
     */
    public data object Revoked : EntitlementState
}
