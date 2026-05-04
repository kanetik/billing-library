package com.kanetik.billing.entitlement

/**
 * Consumer-implemented persistence for [EntitlementCache].
 *
 * The library deliberately does not pick a persistence library — the right
 * choice depends on your app's existing patterns (DataStore, EncryptedSharedPreferences,
 * Room, signed prefs against a server-issued key, etc.). Implement this
 * interface against whatever you already use.
 *
 * ## Contract
 *
 *  - [read] is called once at [EntitlementCache.start] time. Returning
 *    `null` means "no prior state" — the cache starts in
 *    [EntitlementState.Revoked].
 *  - [write] is called on every entitlement-affecting transition (Granted →
 *    snapshot with `isEntitled = true`; Revoked → snapshot with
 *    `isEntitled = false`). [EntitlementState.InGrace] is **not**
 *    persisted; grace re-derives from the most recent confirmed
 *    `confirmedAtMs` on read.
 *  - Both methods are `suspend` so backing implementations can do disk I/O
 *    without blocking. Note the dispatch contexts differ:
 *      - [read] is invoked by `EntitlementCache.start()` in the **caller's**
 *        coroutine context (typically the same scope the caller passes to
 *        `start()`). If your `start()` is launched on `Dispatchers.Main`
 *        (e.g., `viewModelScope.launch { cache.start(viewModelScope) }`),
 *        `read()` runs on Main unless your implementation switches.
 *      - [write] is invoked by an internal writer coroutine launched into
 *        the scope supplied to `start()`, draining a CONFLATED channel
 *        serially.
 *    Your implementation is responsible for any further dispatching it
 *    needs (e.g. wrap the body in `withContext(Dispatchers.IO)` if your
 *    storage isn't already coroutine-friendly). DataStore handles this
 *    transparently; SharedPreferences-backed implementations should
 *    withContext(IO) explicitly.
 *  - [write] should be atomic with respect to [read] — the cache may be
 *    re-created between calls (configuration change, process death) and the
 *    next instance's [read] must see the most recent successful [write].
 *    File-based storage with `commit()` semantics, DataStore, or a synced
 *    `Mutex` around in-memory state all qualify.
 *
 * ## Integrity
 *
 * If your threat model includes users tampering with on-device storage to
 * extend entitlement (e.g. a freemium app where premium has real value),
 * implement [write] / [read] against a signed-prefs layer keyed off a
 * server-issued secret. The [EntitlementSnapshot] type is plain data — no
 * built-in signature or HMAC; the cache trusts what storage returns.
 *
 * For most apps the on-device storage is fine — Play already enforces the
 * authoritative entitlement state on the next successful connect via
 * [com.kanetik.billing.OwnedPurchases.Recovered], so a tampered snapshot
 * gets overwritten the next time the user has connectivity.
 */
public interface EntitlementStorage {

    /**
     * @return the most recently persisted snapshot, or `null` if none exists
     *   (first run, or the snapshot was cleared).
     */
    public suspend fun read(): EntitlementSnapshot?

    /**
     * Persists [snapshot]. Should be atomic with respect to a subsequent
     * [read] call; see the interface-level KDoc for the full contract.
     */
    public suspend fun write(snapshot: EntitlementSnapshot)
}
