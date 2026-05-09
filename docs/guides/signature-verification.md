# Signature verification

Play's signature is the only on-device proof that a `Purchase`'s data is what Google actually sent, untampered. The realistic threat isn't an external app calling your listener (the `PurchasesUpdatedListener` callback runs inside your own process, and PBL doesn't expose it externally); it's tampering between Play and your code. On a rooted device, hooks like Xposed or Frida — or a modified Play Store APK, or a repackaged version of your own app — can swap in arbitrary `Purchase` data before it reaches your collector. PBL hands you `Purchase.signature` and `Purchase.originalJson` but doesn't validate them; that's on you. Without verification, your entitlement checks are trusting whatever data made it through, and on a rooted phone that's whatever the user (or a tool they're running) decides.

`PurchaseVerifier` (in `com.kanetik.billing.security`) does RSA signature verification of `Purchase.originalJson` against your app's public key. Play Console gives you the key when you create the app, under Monetization setup → Licensing. The check itself is small; the consequence of skipping it is that any unverified `Purchase` in your code is essentially user-supplied input.

The recommended integration:

```kotlin
val verifier = PurchaseVerifier(base64PublicKey = BuildConfig.PLAY_BILLING_PUBLIC_KEY)

// Sweep up OwnedPurchases (Live AND Recovered) — both carry purchases that
// need verifying and acknowledging. Filtering only Live would skip recovered
// purchases from a prior session, defeating the auto-recovery feature.
// FlowOutcome is excluded by design: those events describe attempt outcomes,
// not owned state — never grant from their `purchases` list.
billing.observePurchaseUpdates()
    .filterIsInstance<OwnedPurchases>()
    .collect { event ->
        event.purchases.forEach { purchase ->
            if (!verifier.isSignatureValid(purchase)) {
                logger.e(TAG, "Signature mismatch for ${purchase.products}")
                // Don't grant entitlement; consider reporting to your backend.
                return@forEach
            }
            // The library filters already-acknowledged purchases out of the
            // Recovered channel at delivery time, so no consumer-side dedupe
            // is needed. This snippet is just the verify-then-handle skeleton.
            when (val r = billing.handlePurchase(purchase, consume = false)) {
                HandlePurchaseResult.Success -> grantEntitlement(purchase)
                HandlePurchaseResult.AlreadyAcknowledged -> grantEntitlement(purchase) // safe — no PBL call made
                HandlePurchaseResult.NotPurchased -> {} // pending — wait for terminal state
                HandlePurchaseResult.NotOwned -> {
                    // Play says this purchase isn't owned — defer to grace/revoke logic.
                    logger.w(TAG, "handlePurchase NotOwned for ${purchase.products}")
                }
                is HandlePurchaseResult.Failure -> {
                    // Don't grant — recovery sweep retries on the next clean connect.
                    logger.e(TAG, "handlePurchase failed: ${r.exception.userFacingCategory}")
                }
            }
        }
    }
```

`signatureAlgorithm` defaults to `SHA1withRSA` (PBL-current). Override only if you know what you're doing. PBL changes this rarely, and changing it without coordinated server-side changes will fail verification on every purchase.

## See also

- [Purchase recovery](purchase-recovery.md) — how `Recovered` events flow, why no consumer-side dedupe is needed, and the auto-sweep on connect.
- [Error handling](error-handling.md) — full `HandlePurchaseResult` branching.
