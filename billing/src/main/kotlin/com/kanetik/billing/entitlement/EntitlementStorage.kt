package com.kanetik.billing.entitlement

/**
 * Consumer-implemented persistence for [EntitlementCache].
 *
 * The library deliberately does not pick a persistence library — the right
 * choice depends on your app's existing patterns (DataStore, EncryptedSharedPreferences,
 * Room, signed prefs against a server-issued key, etc.). Implement this
 * interface against whatever you already use.
 *
 * ## Generic on K
 *
 * The storage holds **one snapshot per entitlement key**. `K` is whatever
 * type your app uses to discriminate entitlements — a `String` product ID, a
 * sealed class of grants, an `enum`, `Unit` for a single-entitlement app, etc.
 * The cache's `productKeySelector` maps each observed [com.android.billingclient.api.Purchase]
 * to a `K?` (null = doesn't grant any tracked entitlement); the cache holds
 * one [EntitlementState] per key in its [EntitlementCache.state] map; this
 * interface writes / reads one [EntitlementSnapshot] per key.
 *
 * Your implementation is responsible for serializing `K` to a stable on-disk
 * identifier. For `String` keys that's the identity; for `enum class` keys
 * use `K.name` (the constant identifier, not `toString()` — `toString()` can
 * be overridden); for sealed classes pick a stable discriminator field.
 * Stability across app upgrades matters — renaming an enum constant breaks
 * the on-disk mapping.
 *
 * ## Contract
 *
 *  - [readAll] is called once at [EntitlementCache.start] time. Returning an
 *    empty map means "no prior state" — the cache starts with every entitlement
 *    in [EntitlementState.Revoked].
 *  - [write] is called on every entitlement-affecting transition. Each call
 *    carries one `(K, EntitlementSnapshot)` pair; implementations should treat
 *    it as an upsert. Granted transitions write `isEntitled = true`; Revoked
 *    transitions write `isEntitled = false`. [EntitlementState.InGrace] is
 *    **not** persisted; grace re-derives from the most recent confirmed
 *    `confirmedAtMs` on read.
 *  - Both methods are `suspend` so backing implementations can do disk I/O
 *    without blocking. Note the dispatch contexts differ:
 *      - [readAll] is invoked by `EntitlementCache.start()` in the **caller's**
 *        coroutine context (typically the same scope the caller passes to
 *        `start()`). If your `start()` is launched on `Dispatchers.Main`
 *        (e.g., `viewModelScope.launch { cache.start(viewModelScope) }`),
 *        `readAll()` runs on Main unless your implementation switches.
 *      - [write] is invoked by an internal writer coroutine launched into
 *        the scope supplied to `start()`, draining an UNLIMITED `(K, snapshot)`
 *        channel in send order. Calls are serialised; there is no per-key
 *        conflation, so back-to-back writes for the same key both reach
 *        storage. Entitlement transitions happen at human pace (PBL events,
 *        grace ticks), so the buffer is bounded in practice by activity.
 *    Your implementation is responsible for any further dispatching it needs
 *    (e.g. wrap the body in `withContext(Dispatchers.IO)` if your storage
 *    isn't already coroutine-friendly). DataStore handles this transparently;
 *    SharedPreferences-backed implementations should withContext(IO) explicitly.
 *  - [write] should be atomic with respect to [readAll] — the cache may be
 *    re-created between calls (configuration change, process death) and the
 *    next instance's [readAll] must see the most recent successful [write] for
 *    every key.
 *
 * ## Integrity
 *
 * The [EntitlementSnapshot] type is plain data — no built-in signature or
 * HMAC; the cache trusts what storage returns. If your threat model includes
 * users tampering with on-device storage to extend entitlement (e.g. a
 * freemium app where the paid entitlement has real value), wrap your
 * implementation in
 * [com.kanetik.billing.entitlement.signed.SignedEntitlementStorage] — a
 * decorator that signs each snapshot on write and verifies it on read using
 * an HMAC key from a [com.kanetik.billing.entitlement.signed.HmacKeyProvider]
 * (recommended default: [com.kanetik.billing.entitlement.signed.KeystoreBackedKeyProvider],
 * which is Android Keystore-backed). The decorator stays neutral on the
 * persistence backend; you only have to wire up signing once.
 *
 * For most apps the on-device storage is fine — Play already enforces the
 * authoritative entitlement state on the next successful connect via
 * [com.kanetik.billing.OwnedPurchases.Recovered], so a tampered snapshot
 * gets overwritten the next time the user has connectivity.
 */
public interface EntitlementStorage<K : Any> {

    /**
     * @return every persisted snapshot, keyed by entitlement key. Empty map
     *   means "no prior state" — the cache starts with every entitlement in
     *   [EntitlementState.Revoked].
     */
    public suspend fun readAll(): Map<K, EntitlementSnapshot>

    /**
     * Persists [snapshot] under [key]. Upsert semantics: replaces any previous
     * snapshot for the same key. Should be atomic with respect to a subsequent
     * [readAll] call; see the interface-level KDoc for the full contract.
     */
    public suspend fun write(key: K, snapshot: EntitlementSnapshot)
}
