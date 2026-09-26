package com.kanetik.billing.entitlement

/**
 * Persistable snapshot of the cache's most recent confirmed observation.
 *
 * Written by [EntitlementCache] on every entitlement-affecting event — both
 * Granted (after a successful confirm) and Revoked (after a matching
 * `PurchaseRevoked` event). Read on construction so the cache can hydrate
 * from disk before the first network round-trip completes.
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
 *   was confirmed.
 * @property purchaseToken The Play Billing purchase token of the matching
 *   purchase. Set when [isEntitled] is `true`, and ALSO carried forward on
 *   transitions to [isEntitled] = `false` so a persisted Revoked snapshot
 *   still ties back to the purchase that was revoked via `PurchaseRevoked`.
 *   Null only when the cache has never observed a
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
