package com.kanetik.billing.entitlement.signed

/**
 * Persistence for the signature blobs written alongside each
 * [com.kanetik.billing.entitlement.EntitlementSnapshot] in a multi-entitlement
 * cache.
 *
 * Each blob is small — 4 bytes of version plus 32 bytes of HMAC-SHA256 (~36
 * total) — and there's one per entitlement key. Blobs must outlive process
 * death and survive the same restarts that the snapshots themselves do, so
 * that [SignedEntitlementStorage.readAll] can verify each snapshot returned
 * by the delegate.
 *
 * Default implementation: [SharedPreferencesSignatureStore]. Consumers with
 * existing storage layers (DataStore, encrypted prefs, Room) can implement
 * this interface against whatever backend they prefer; the library remains
 * neutral on the persistence choice.
 *
 * Atomicity expectations match
 * [com.kanetik.billing.entitlement.EntitlementStorage] — a successful
 * [writeSignature] for `entitlementKey` must be visible to the next process's
 * [readSignature] for the same key. The [entitlementKey] is the
 * already-serialized string identifier produced by
 * [SignedEntitlementStorage]'s `keyToStorageId` lambda; this interface stays
 * agnostic to the consumer's `K` type.
 */
public interface SignatureStore {

    /**
     * @return the most recently persisted signature blob for [entitlementKey],
     *   or `null` if none exists (first run, or the blob was cleared).
     */
    public suspend fun readSignature(entitlementKey: String): ByteArray?

    /**
     * Persists [signature] under [entitlementKey]. Upsert semantics: replaces
     * any previous blob for the same key. Must be atomic with respect to a
     * subsequent [readSignature] call.
     */
    public suspend fun writeSignature(entitlementKey: String, signature: ByteArray)

    /**
     * Removes the persisted signature blob for [entitlementKey], if any. The
     * cache itself doesn't call this; provided so consumers can wipe a
     * specific entitlement's signature (e.g., a "reset entitlement"
     * debug/dev affordance) without losing the rest. A subsequent
     * [readSignature] for the same key returns `null`.
     */
    public suspend fun clearSignature(entitlementKey: String)
}
