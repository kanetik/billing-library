package com.kanetik.billing.ext

import android.util.Base64
import com.android.billingclient.api.Purchase
import com.kanetik.billing.logging.BillingLogger
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec

/**
 * Verifies that a [Purchase]'s `originalJson` was actually signed by Google Play
 * using the app's licensing public key.
 *
 * Adapted from Google's reference Security helper (`Trivial Drive` sample),
 * generalized so the public key isn't pinned to a specific build-config field —
 * the consumer supplies it at construction time.
 *
 * ## Where to get the key
 *
 * In Play Console → Monetize → In-app products → Licensing → Base64 RSA public key.
 * Paste it as a string into the constructor (typically wired up via `BuildConfig`
 * with the value sourced from a CI secret, never committed).
 *
 * ## Why verify
 *
 * Without signature verification, any client able to forge a Play Store purchase
 * response could grant themselves premium for free. Verification is the difference
 * between "anyone with `adb` can flip the premium flag" and "you'd need Google's
 * signing key to forge a grant." Take the 2 minutes to wire this up.
 *
 * ## Usage
 *
 * ```
 * private val verifier = PurchaseVerifier(BuildConfig.PLAY_LICENSE_KEY, logger)
 *
 * suspend fun handlePurchase(purchase: Purchase) {
 *     if (!verifier.isSignatureValid(purchase)) {
 *         // Reject — possible tampering or misconfigured key.
 *         return
 *     }
 *     billing.acknowledgePurchase(...)
 * }
 * ```
 *
 * ## Behavior on a blank or malformed key
 *
 * If [base64PublicKey] is blank, every call to [isSignatureValid] returns `false`
 * (rejects). If the key is non-blank but cannot be parsed as a valid RSA key,
 * the first call logs an error via [logger] and every call returns `false`.
 *
 * If you want a different policy (e.g. "skip verification entirely when the key
 * is missing in debug builds"), wrap [PurchaseVerifier] in your own helper that
 * checks the key before constructing — don't construct with a blank key and
 * expect graceful fall-through.
 */
public class PurchaseVerifier(
    private val base64PublicKey: String,
    private val logger: BillingLogger = BillingLogger.Noop
) {

    /**
     * Lazily parsed once on first valid-key call. Null on blank or malformed keys
     * so [isSignatureValid] can distinguish and reject.
     */
    private val cachedPublicKey: PublicKey? by lazy { parsePublicKeyOrNull(base64PublicKey) }

    /**
     * Returns true if [purchase]'s `originalJson` matches its `signature` under
     * the configured public key.
     */
    public fun isSignatureValid(purchase: Purchase): Boolean {
        if (base64PublicKey.isBlank()) {
            logger.e("PurchaseVerifier received a blank public key — rejecting purchase")
            return false
        }
        val key = cachedPublicKey ?: run {
            logger.e("PurchaseVerifier could not parse the public key — rejecting purchase")
            return false
        }
        return verify(key, purchase.originalJson, purchase.signature)
    }

    private fun verify(publicKey: PublicKey, signedData: String, signature: String): Boolean {
        if (signedData.isEmpty() || signature.isEmpty()) {
            logger.w("Purchase verification failed: missing signedData or signature")
            return false
        }
        return try {
            val sig = Signature.getInstance(SIGNATURE_ALGORITHM).apply {
                initVerify(publicKey)
                // Play Store signs purchase JSON as UTF-8; the implicit platform default
                // would work in practice on Android but is a latent portability bug.
                update(signedData.toByteArray(Charsets.UTF_8))
            }
            val signatureBytes = Base64.decode(signature, Base64.DEFAULT)
            if (!sig.verify(signatureBytes)) {
                logger.w("Purchase signature verification FAILED — possible tampering")
                false
            } else {
                true
            }
        } catch (e: IllegalArgumentException) {
            logger.e("Purchase verification failed: malformed Base64 signature", e)
            false
        } catch (e: Exception) {
            logger.e("Purchase verification failed unexpectedly", e)
            false
        }
    }

    private fun parsePublicKeyOrNull(encodedPublicKey: String): PublicKey? {
        if (encodedPublicKey.isBlank()) return null
        return try {
            val decodedKey = Base64.decode(encodedPublicKey, Base64.DEFAULT)
            val keyFactory = KeyFactory.getInstance(KEY_FACTORY_ALGORITHM)
            keyFactory.generatePublic(X509EncodedKeySpec(decodedKey))
        } catch (e: Exception) {
            logger.e("Failed to parse public key as RSA", e)
            null
        }
    }

    private companion object {
        private const val KEY_FACTORY_ALGORITHM = "RSA"
        private const val SIGNATURE_ALGORITHM = "SHA1withRSA"
    }
}
