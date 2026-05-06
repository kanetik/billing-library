package com.kanetik.billing.entitlement.signed

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Default [SeedCache] that persists the per-install seed in a private
 * SharedPreferences file.
 *
 * **The cached seed is plaintext.** An attacker with on-device write access
 * can extract it and forge fresh signatures, defeating tamper resistance. Use
 * [KeystoreBackedKeyProvider] instead if your threat model can rely on
 * hardware-backed Keystore. See [ServerSeededKeyProvider] for the full
 * discussion.
 *
 * @param context any [Context]; only `applicationContext` is retained.
 * @param fileName SharedPreferences file name. Defaults to a stable
 *   library-namespaced value.
 */
public class SharedPreferencesSeedCache(
    context: Context,
    private val fileName: String = DEFAULT_FILE_NAME,
) : SeedCache {

    private val appContext: Context = context.applicationContext

    private val prefs by lazy {
        appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    override suspend fun read(): ByteArray? = withContext(Dispatchers.IO) {
        val encoded = prefs.getString(KEY_SEED, null) ?: return@withContext null
        runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
    }

    override suspend fun write(seed: ByteArray) {
        withContext(Dispatchers.IO) {
            val encoded = Base64.encodeToString(seed, Base64.NO_WRAP)
            prefs.edit().putString(KEY_SEED, encoded).commit()
        }
    }

    public companion object {
        public const val DEFAULT_FILE_NAME: String =
            "com.kanetik.billing.signed_entitlement.seed"

        private const val KEY_SEED = "seed"
    }
}
