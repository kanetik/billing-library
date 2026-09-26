package com.kanetik.billing.entitlement

/**
 * The cached entitlement state for a single key, surfaced by
 * [EntitlementCache] via its `state: StateFlow<Map<K, EntitlementState>>`
 * (and the per-key [EntitlementCache.stateFor] convenience).
 *
 *  - [Granted] — the cache has confirmed the user owns a matching purchase.
 *    Show the gated UI / unlock the feature / etc.
 *  - [Revoked] — the cache has never seen entitlement for this key, or an
 *    explicit [com.kanetik.billing.PurchaseRevoked] event revoked it. Hide
 *    the gated UI.
 *
 * Branch on the sealed type to render UI rather than comparing instances by
 * equality.
 */
public sealed interface EntitlementState {

    /**
     * The user owns a matching purchase for this key. Show the gated UI /
     * unlock the corresponding feature.
     *
     * Reached on:
     *  - A [com.kanetik.billing.OwnedPurchases.Live] containing a purchase
     *    that this cache's `productKeySelector` maps to this key.
     *  - A [com.kanetik.billing.OwnedPurchases.Recovered] containing a
     *    matching purchase (the recovery sweep on connect).
     *  - A persisted [EntitlementSnapshot] read at start with `isEntitled = true`.
     */
    public data object Granted : EntitlementState

    /**
     * No entitlement for this key. Hide the gated UI.
     *
     * Reached on:
     *  - A [com.kanetik.billing.PurchaseRevoked] event whose `purchaseToken`
     *    matches the cached snapshot's last confirmed purchase for this key.
     *    The consumer pushes these via `emitExternalRevocation` from their
     *    RTDN→FCM (or polling, or deeplink) pipeline.
     *  - The implicit default for any key absent from the state map (no
     *    prior snapshot, nothing has arrived yet).
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
