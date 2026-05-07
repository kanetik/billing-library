package com.kanetik.billing.entitlement.signed

import java.security.MessageDigest

/**
 * Source of the HMAC key used by [SignedEntitlementStorage] to sign and
 * verify [com.kanetik.billing.entitlement.EntitlementSnapshot] persistence.
 *
 * Implementations expose `sign` / `verify` rather than returning a raw key,
 * so that providers backed by non-extractable hardware keys (Android Keystore
 * with a hardware-backed `SecretKey`) can use the key without exposing it.
 *
 * # Threat-model caveats
 *
 *  - **The library cannot enforce key non-extractability.** A consumer who
 *    hardcodes a key in `BuildConfig` and writes their own
 *    `HmacKeyProvider` over it defeats the whole point of this layer. Use
 *    [KeystoreBackedKeyProvider] (the easy-and-safe default) unless you have
 *    a specific reason not to.
 *  - **Not a replacement for server-side validation.** This provides
 *    on-device tamper resistance. Apps with strong threat models still need a
 *    backend purchase-validation pipeline (Voided Purchases API for one-time
 *    products, RTDN for subscriptions).
 *  - **Not a replacement for Tink.** This helper is scoped to
 *    [com.kanetik.billing.entitlement.EntitlementStorage]. General-purpose
 *    crypto is the consumer's concern.
 *
 * # Custom implementations
 *
 * Override [sign] and (optionally) [verify]. The default [verify] re-signs
 * and constant-time-compares via [MessageDigest.isEqual]; that's correct for
 * any deterministic HMAC, so most implementations only need [sign].
 */
public interface HmacKeyProvider {

    /**
     * Returns `HMAC(key, data)` for whichever key this provider holds.
     *
     * Must be deterministic for a given `(key, data)` pair — verification
     * relies on byte-equal output. Implementations that need to lazily
     * generate or fetch the key may suspend on first invocation; subsequent
     * calls should be hot.
     */
    public suspend fun sign(data: ByteArray): ByteArray

    /**
     * Returns `true` iff [signature] equals `HMAC(key, data)`.
     *
     * Default: re-sign and compare in constant time. Override only if your
     * provider has a faster verification path (rare).
     */
    public suspend fun verify(data: ByteArray, signature: ByteArray): Boolean {
        val expected = sign(data)
        return MessageDigest.isEqual(expected, signature)
    }
}
