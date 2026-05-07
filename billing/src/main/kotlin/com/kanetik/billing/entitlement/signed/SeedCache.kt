package com.kanetik.billing.entitlement.signed

/**
 * Persistence for the per-install seed used by [ServerSeededKeyProvider].
 *
 * The seed is fetched from a backend exactly once (on first sign / verify
 * after install or cache miss) and cached locally for the lifetime of the
 * install. This interface is the cache.
 *
 * Default implementation: [SharedPreferencesSeedCache]. Consumers can
 * implement against any backend they prefer; the seed is opaque bytes.
 *
 * **Caveat:** the default cache stores the seed in plaintext. An attacker who
 * can write to on-device storage to tamper with the snapshot can also extract
 * the cached seed and forge fresh signatures, defeating the tamper resistance
 * the seed is meant to provide. If your threat model requires hardware-backed
 * protection, prefer [KeystoreBackedKeyProvider] over [ServerSeededKeyProvider]
 * — the Keystore key is non-extractable on devices with hardware-backed
 * Keystore. See [ServerSeededKeyProvider] for the full caveat discussion.
 */
public interface SeedCache {

    /**
     * Returns the cached seed bytes, or `null` if no seed has been cached
     * yet (first run after install).
     */
    public suspend fun read(): ByteArray?

    /**
     * Persists [seed]. Must be atomic with respect to a subsequent [read].
     */
    public suspend fun write(seed: ByteArray)
}
