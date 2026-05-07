package com.kanetik.billing.entitlement.signed

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

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
        // Treat decode failure as corruption, not cache miss. Returning null
        // here would let ServerSeededKeyProvider re-fetch a fresh seed,
        // silently invalidating every signature that was written against the
        // original seed — the same outcome an attacker corrupting the file
        // would want. Throwing surfaces the corruption to the caller; they
        // can decide whether to clear the cache and re-fetch deliberately or
        // bail out. (Asymmetric with SharedPreferencesSignatureStore.read,
        // which returns a shape-invalid blob — that path has the
        // SignedEntitlementStorage size check downstream to convert it to
        // InvalidSignature; the seed has no equivalent shape check.)
        try {
            Base64.decode(encoded, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            throw IOException("Stored seed in SharedPreferences (file=$fileName) is not valid Base64; cache is corrupt.", e)
        }
    }

    override suspend fun write(seed: ByteArray) {
        withContext(Dispatchers.IO) {
            val encoded = Base64.encodeToString(seed, Base64.NO_WRAP)
            // commit() reports false on a failed synchronous disk write. If we
            // swallow that, ServerSeededKeyProvider may re-fetch on the next
            // session (legitimate fetch), but worse, on the *current* session
            // it caches a seed the next process can't see — so signatures
            // written this session won't verify next session. Surface it.
            val ok = prefs.edit().putString(KEY_SEED, encoded).commit()
            if (!ok) {
                throw IOException("Failed to persist seed to SharedPreferences (file=$fileName)")
            }
        }
    }

    public companion object {
        public const val DEFAULT_FILE_NAME: String =
            "com.kanetik.billing.signed_entitlement.seed"

        private const val KEY_SEED = "seed"
    }
}
