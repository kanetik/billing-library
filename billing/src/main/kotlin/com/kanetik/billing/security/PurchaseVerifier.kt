package com.kanetik.billing.security

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
 *     billing.acknowledgePurchase(purchase)
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
 *
 * ## Signature algorithm
 *
 * Defaults to `SHA1withRSA`, which is what Play Store currently uses to sign
 * purchase data (per Play Console's Licensing docs). SHA-1 for signature use is
 * formally deprecated by NIST and Android's security documentation; if Google
 * ever migrates to `SHA256withRSA` (which they have signaled), pass the new
 * algorithm name as [signatureAlgorithm] without waiting for a library release:
 *
 * ```
 * PurchaseVerifier(key, logger, signatureAlgorithm = "SHA256withRSA")
 * ```
 *
 * @param base64PublicKey RSA public key from Play Console, Base64-encoded.
 * @param logger Optional sink for diagnostic messages. Defaults to [BillingLogger.Noop].
 * @param signatureAlgorithm JCA signature algorithm name. Defaults to the
 *   Play-current `SHA1withRSA`.
 */
public class PurchaseVerifier(
    private val base64PublicKey: String,
    private val logger: BillingLogger = BillingLogger.Noop,
    private val signatureAlgorithm: String = DEFAULT_SIGNATURE_ALGORITHM
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
            val sig = Signature.getInstance(signatureAlgorithm).apply {
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

    public companion object {
        /**
         * Play Store's current signature algorithm. SHA-1 + RSA. If Google ever
         * migrates, pass the new algorithm name to [PurchaseVerifier]'s
         * [signatureAlgorithm] parameter without waiting for a library release.
         */
        public const val DEFAULT_SIGNATURE_ALGORITHM: String = "SHA1withRSA"

        private const val KEY_FACTORY_ALGORITHM = "RSA"
    }
}
