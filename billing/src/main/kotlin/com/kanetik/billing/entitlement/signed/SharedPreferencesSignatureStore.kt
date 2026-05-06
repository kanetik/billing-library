package com.kanetik.billing.entitlement.signed

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Default [SignatureStore] that persists the signature blob in a private
 * SharedPreferences file.
 *
 * Bytes are stored as a base64 string (SharedPreferences doesn't support
 * `byte[]` natively). I/O is wrapped in `withContext(Dispatchers.IO)` so
 * callers can invoke [readSignature] / [writeSignature] from any dispatcher
 * without blocking.
 *
 * @param context any [Context]; only `applicationContext` is retained.
 * @param fileName SharedPreferences file name. Defaults to a stable
 *   library-namespaced value. Override only if you have a reason to (multiple
 *   independent caches in one app, test isolation, etc.).
 */
public class SharedPreferencesSignatureStore(
    context: Context,
    private val fileName: String = DEFAULT_FILE_NAME,
) : SignatureStore {

    private val appContext: Context = context.applicationContext

    private val prefs by lazy {
        appContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    }

    override suspend fun readSignature(): ByteArray? = withContext(Dispatchers.IO) {
        val encoded = prefs.getString(KEY_SIGNATURE, null) ?: return@withContext null
        runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrNull()
    }

    override suspend fun writeSignature(signature: ByteArray) {
        withContext(Dispatchers.IO) {
            val encoded = Base64.encodeToString(signature, Base64.NO_WRAP)
            prefs.edit().putString(KEY_SIGNATURE, encoded).commit()
        }
    }

    public companion object {
        public const val DEFAULT_FILE_NAME: String =
            "com.kanetik.billing.signed_entitlement.signature"

        private const val KEY_SIGNATURE = "signature"
    }
}
