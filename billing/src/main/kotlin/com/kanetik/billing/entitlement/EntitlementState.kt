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
 * Branch on the sealed type to render UI; never compare instances by equality
 * across emissions (the [InGrace.expiresAtMs] timestamp will differ for each
 * tick).
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
     *  - A [com.kanetik.billing.OwnedPurchases.Recovered] event whose
     *    purchase list does *not* contain a match. The cache trusts every
     *    Recovered as authoritative — Play's connect-time sweep guarantees
     *    a Recovered emission on every successful connection, so an empty
     *    Recovered isn't a stale signal.
     *  - An [InGrace] state whose [InGrace.expiresAtMs] has elapsed without
     *    recovery.
     *  - The default state when no prior snapshot exists and nothing has
     *    arrived yet.
     */
    public data object Revoked : EntitlementState
}
