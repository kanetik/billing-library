package com.kanetik.billing.entitlement

/**
 * Persistable snapshot of the cache's most recent confirmed observation.
 *
 * Written by [EntitlementCache] on every entitlement-affecting event — both
 * Granted (after a successful confirm) and Revoked (after a matching
 * `PurchaseRevoked` event, or after grace expires). Read on construction so
 * the cache can hydrate from disk before the first network round-trip
 * completes.
 *
 * Note: only confirmed observations are persisted — the cache does **not**
 * persist [EntitlementState.InGrace]. Persisting grace would let an attacker
 * who can manipulate the storage layer extend the window indefinitely;
 * letting grace re-derive from a confirmed [confirmedAtMs] on read keeps the
 * window honest.
 *
 * Consumers that need integrity-protected storage (signed prefs, encrypted
 * DataStore, etc.) implement [EntitlementStorage] against their own layer;
 * the library deliberately ships no persistence implementation. Pick whatever
 * matches your existing app patterns — sample uses an in-memory map for
 * brevity.
 *
 * @property isEntitled `true` if the cache last confirmed entitlement;
 *   `false` if it last confirmed *no* matching purchase.
 * @property confirmedAtMs The clock value (in the cache's injected clock's
 *   units — typically `System.currentTimeMillis()`) at which the snapshot
 *   was confirmed. Used by the cache to evaluate whether a persisted grace
 *   window has already expired.
 * @property purchaseToken The Play Billing purchase token of the matching
 *   purchase. Set when [isEntitled] is `true`, and ALSO carried forward on
 *   transitions to [isEntitled] = `false` so a persisted Revoked snapshot
 *   still ties back to the purchase whose grace expired or that was revoked
 *   via `PurchaseRevoked`. Null only when the cache has never observed a
 *   matching purchase (initial state with no prior session) or when the
 *   matching purchase didn't carry a token (shouldn't happen in practice —
 *   Play guarantees a token on PURCHASED state — but the field is nullable
 *   for safety). Useful for downstream signature verification or server-side
 *   reconciliation.
 */
public data class EntitlementSnapshot(
    public val isEntitled: Boolean,
    public val confirmedAtMs: Long,
    public val purchaseToken: String?,
)
