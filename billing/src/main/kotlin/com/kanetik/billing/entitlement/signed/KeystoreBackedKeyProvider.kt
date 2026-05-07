package com.kanetik.billing.entitlement.signed

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/**
 * [HmacKeyProvider] backed by an HMAC-SHA256 key stored in the Android
 * Keystore. The recommended default for tamper-resistant on-device signing.
 *
 * On first sign / verify, generates a fresh `HmacSHA256` key with
 * `PURPOSE_SIGN | PURPOSE_VERIFY` under [keyAlias] and persists it in the
 * Keystore. Subsequent calls reuse the same key. Once generated, the key
 * never leaves Keystore — [sign] uses [Mac.init] with the Keystore-backed
 * [SecretKey], and the signing operation runs through the Keystore daemon.
 *
 * On devices with hardware-backed Keystore (most modern Android), the key is
 * non-extractable: a rooted attacker cannot read it out of memory or pull it
 * off disk. On devices without hardware-backed Keystore, the key is software
 * protected only — better than nothing, but not unbreakable. The library
 * does not enforce hardware backing; that's the OEM's call.
 *
 * # Key alias
 *
 * Defaults to [DEFAULT_KEY_ALIAS]. Override only if you have a reason
 * (multiple independent caches in one app, separating dev/release keys,
 * etc.). The alias is the identity of the key — losing it (uninstall, app
 * data clear, factory reset) means existing signatures fail to verify, and
 * the cache cold-starts on next launch.
 *
 * # Usage
 *
 * ```kotlin
 * val keyProvider = KeystoreBackedKeyProvider.create()
 * val storage = SignedEntitlementStorage(
 *     delegate = myEntitlementStorage,
 *     keyProvider = keyProvider,
 *     signatureStore = SharedPreferencesSignatureStore(context),
 * )
 * ```
 */
public class KeystoreBackedKeyProvider private constructor(
    private val keyAlias: String,
) : HmacKeyProvider {

    // Serialize key creation to avoid two parallel first-uses both calling
    // KeyGenerator.generateKey() and racing on the alias.
    private val keyMutex = Mutex()

    @Volatile
    private var cachedKey: SecretKey? = null

    override suspend fun sign(data: ByteArray): ByteArray = withContext(Dispatchers.IO) {
        val key = loadOrGenerateKey()
        val mac = Mac.getInstance(MAC_ALGORITHM)
        mac.init(key)
        mac.doFinal(data)
    }

    private suspend fun loadOrGenerateKey(): SecretKey {
        cachedKey?.let { return it }
        return keyMutex.withLock {
            cachedKey?.let { return@withLock it }

            val keystore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val existing = keystore.getKey(keyAlias, null) as? SecretKey
            val key = existing ?: generateKey()
            cachedKey = key
            key
        }
    }

    private fun generateKey(): SecretKey {
        val generator = KeyGenerator.getInstance(MAC_ALGORITHM, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
        ).build()
        generator.init(spec)
        return generator.generateKey()
    }

    public companion object {
        public const val DEFAULT_KEY_ALIAS: String =
            "com.kanetik.billing.signed_entitlement.hmac_key"

        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val MAC_ALGORITHM = "HmacSHA256"

        /**
         * Returns a provider for the named [keyAlias], lazily generating the
         * Keystore entry on first sign / verify.
         */
        public fun create(
            keyAlias: String = DEFAULT_KEY_ALIAS,
        ): KeystoreBackedKeyProvider = KeystoreBackedKeyProvider(keyAlias)
    }
}
