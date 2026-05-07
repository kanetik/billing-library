package com.kanetik.billing.entitlement.signed

/**
 * Persistence for the signature blob written alongside an
 * [com.kanetik.billing.entitlement.EntitlementSnapshot].
 *
 * The blob is small — 4 bytes of version plus 32 bytes of HMAC-SHA256 (~36
 * total). It must outlive process death and survive the same restarts that
 * the snapshot itself does, so that
 * [SignedEntitlementStorage.read] can verify the snapshot returned by the
 * delegate.
 *
 * Default implementation: [SharedPreferencesSignatureStore]. Consumers with
 * existing storage layers (DataStore, encrypted prefs, Room) can implement
 * this interface against whatever backend they prefer; the library remains
 * neutral on the persistence choice.
 *
 * Atomicity expectations match
 * [com.kanetik.billing.entitlement.EntitlementStorage] — a successful
 * [writeSignature] must be visible to the next process's [readSignature].
 * Returning the signature for a *different* snapshot than the delegate
 * returns is fine in steady state (the verification will fail, the read
 * returns null, and the next live confirm restores) but should not be the
 * common case; callers usually pair a single [SignatureStore] with a single
 * delegate so writes interleave consistently.
 */
public interface SignatureStore {

    /**
     * Returns the most recently persisted signature blob, or `null` if none
     * exists (first run, or the blob was cleared).
     */
    public suspend fun readSignature(): ByteArray?

    /**
     * Persists [signature]. Must be atomic with respect to a subsequent
     * [readSignature] call.
     */
    public suspend fun writeSignature(signature: ByteArray)
}
