package com.kanetik.billing.entitlement.signed

import android.content.Context
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * Default [SignatureStore] that persists signature blobs in a private
 * SharedPreferences file.
 *
 * Each entitlement key's blob is stored under prefs key
 * `"signature:" + entitlementKey`. Bytes are stored as a base64 string
 * (SharedPreferences doesn't support `byte[]` natively). I/O is wrapped in
 * `withContext(Dispatchers.IO)` so callers can invoke [readSignature] /
 * [writeSignature] / [clearSignature] from any dispatcher without blocking.
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

    override suspend fun readSignature(entitlementKey: String): ByteArray? = withContext(Dispatchers.IO) {
        val encoded = prefs.getString(prefsKey(entitlementKey), null) ?: return@withContext null
        // A non-decodable string means the prefs file is corrupt or someone
        // tampered with the signature. Return an empty (and therefore
        // shape-invalid) blob so SignedEntitlementStorage's size check fires
        // InvalidSignature, not MissingSignature — surfacing this as
        // "missing" would under-report tampering.
        runCatching { Base64.decode(encoded, Base64.NO_WRAP) }.getOrDefault(ByteArray(0))
    }

    override suspend fun writeSignature(entitlementKey: String, signature: ByteArray) {
        withContext(Dispatchers.IO) {
            val encoded = Base64.encodeToString(signature, Base64.NO_WRAP)
            // commit() reports false when the synchronous disk write fails
            // (storage full, permissions, etc.). Silently dropping that would
            // violate SignatureStore's "atomic with respect to readSignature"
            // contract — the next read would return a stale blob and verify
            // against a fresh snapshot, surfacing as a false-positive
            // InvalidSignature. Surface the failure instead.
            val ok = prefs.edit().putString(prefsKey(entitlementKey), encoded).commit()
            if (!ok) {
                throw IOException("Failed to persist signature to SharedPreferences (file=$fileName, key=$entitlementKey)")
            }
        }
    }

    override suspend fun clearSignature(entitlementKey: String) {
        withContext(Dispatchers.IO) {
            val ok = prefs.edit().remove(prefsKey(entitlementKey)).commit()
            if (!ok) {
                throw IOException("Failed to remove signature from SharedPreferences (file=$fileName, key=$entitlementKey)")
            }
        }
    }

    private fun prefsKey(entitlementKey: String): String = PREFS_KEY_PREFIX + entitlementKey

    public companion object {
        public const val DEFAULT_FILE_NAME: String =
            "com.kanetik.billing.signed_entitlement.signature"

        private const val PREFS_KEY_PREFIX = "signature:"
    }
}
