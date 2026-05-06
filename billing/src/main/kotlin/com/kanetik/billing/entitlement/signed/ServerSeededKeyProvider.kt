package com.kanetik.billing.entitlement.signed

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * [HmacKeyProvider] backed by a per-install seed fetched from a backend on
 * first use, then cached locally via [SeedCache].
 *
 * Intended for consumers with auth infrastructure who want a key that's
 * unique per install and revocable server-side (rotate the seed, invalidate
 * all existing snapshots). The HMAC is computed in software over the cached
 * seed using `HmacSHA256`.
 *
 * # Caveat: cached seed is local
 *
 * The seed is cached locally so that subsequent signs don't round-trip to
 * the server. If the cache is plaintext (the default
 * [SharedPreferencesSeedCache] is plaintext), an attacker with on-device
 * write access — the same attacker the signing layer is meant to defend
 * against — can extract the seed and forge fresh signatures. **In that
 * threat model, this provider gives you no real tamper resistance.** Prefer
 * [KeystoreBackedKeyProvider] if your devices have hardware-backed Keystore
 * available.
 *
 * The provider is still useful when:
 *  - The app does its own at-rest encryption around the seed cache and you
 *    just want a clean abstraction over the per-install secret. (Wrap
 *    [SeedCache] with your own encrypting decorator.)
 *  - Server-side seed rotation is more important than tamper resistance per
 *    se (e.g., revoke a compromised install on demand).
 *  - You want to combine this provider with [KeystoreBackedKeyProvider] in
 *    a layered scheme — but the library does not ship that layering today.
 *
 * # Fetcher contract
 *
 *  - [fetchSeed] is called at most once per install (until the cache is
 *    cleared).
 *  - Failures propagate. The caller of [sign] / [verify] receives whatever
 *    the fetcher throws. Wrap with retries or fallback in your fetcher
 *    implementation if you need that behaviour.
 *  - The returned bytes are used as-is; the library does not derive,
 *    truncate, or hash them. 32 random bytes is a reasonable seed size for
 *    HMAC-SHA256.
 *
 * @param fetchSeed lambda invoked on first cache miss to obtain the seed
 *   from a backend. Receives no arguments; the consumer's auth /
 *   identification of the install is its responsibility.
 * @param cache local persistence for the fetched seed. Default
 *   [SharedPreferencesSeedCache] is plaintext; see caveat above.
 */
public class ServerSeededKeyProvider(
    private val fetchSeed: suspend () -> ByteArray,
    private val cache: SeedCache,
) : HmacKeyProvider {

    private val seedMutex = Mutex()

    @Volatile
    private var cachedSeed: ByteArray? = null

    override suspend fun sign(data: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val seed = loadOrFetchSeed()
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(SecretKeySpec(seed, MAC_ALGORITHM))
        mac.doFinal(data)
    }

    private suspend fun loadOrFetchSeed(): ByteArray {
        cachedSeed?.let { return it }
        return seedMutex.withLock {
            cachedSeed?.let { return@withLock it }

            val persisted = cache.read()
            val seed = persisted ?: fetchSeed().also { cache.write(it) }
            cachedSeed = seed
            seed
        }
    }

    private companion object {
        const val MAC_ALGORITHM = "HmacSHA256"
    }
}
