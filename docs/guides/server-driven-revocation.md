# Server-driven revocation

## Real-Time Developer Notifications (RTDN)

RTDN is server-side — Cloud Pub/Sub from Play to your backend. The library is client-side only. For RTDN integration, see Google's [Real-time developer notifications guide](https://developer.android.com/google/play/billing/getting-ready#configure-rtdn).

If your backend posts notifications back to the client (e.g., "subscription state changed"), call `queryPurchases` to refresh and let the existing `observePurchaseUpdates` pipeline handle the result.

## Emitting revocation events

Refunds, chargebacks, and other server-driven entitlement reversals don't come through PBL's `PurchasesUpdatedListener`. They originate on Play's side, hit your backend through RTDN → Cloud Pub/Sub, and reach the app over whatever transport you've wired (FCM push, polling, deeplink). `BillingRepository` exposes one entry point for that:

```kotlin
public suspend fun emitExternalRevocation(purchaseToken: String, reason: RevocationReason)
```

The library does **not** subscribe to FCM, RTDN, or anything server-side — that's your decision (transport, auth, decoding). Your FCM listener (or polling worker, etc.) decodes the payload into a `(purchaseToken, RevocationReason)` pair and calls `emitExternalRevocation`. The event surfaces as `PurchaseRevoked` through the same flow you already collect:

```kotlin
class FcmRevocationReceiver : FirebaseMessagingService() {
    @Inject lateinit var billing: BillingRepository

    // Service-scoped CoroutineScope so emitExternalRevocation runs without
    // blocking the FCM callback thread. SupervisorJob keeps a single emit
    // failure from cancelling sibling work; the scope is cancelled in
    // onDestroy below so emits don't outlive the service.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(message: RemoteMessage) {
        val token = message.data["purchaseToken"] ?: return
        // Message-type strings here are app-defined — what your backend chose
        // to put in the FCM payload. They don't have to match RTDN's
        // SUBSCRIPTION_REVOKED / SUBSCRIPTION_EXPIRED constants; the backend
        // already decoded those before sending the FCM. Pick whatever's
        // convenient for your backend ↔ client contract.
        val reason = when (message.data["type"]) {
            "REFUND" -> RevocationReason.Refunded
            "CHARGEBACK" -> RevocationReason.Chargeback
            "SUBS_EXPIRED" -> RevocationReason.SubscriptionExpired
            else -> RevocationReason.Other
        }
        scope.launch { billing.emitExternalRevocation(token, reason) }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
```

(For longer-running work or for guaranteed delivery across process death, use WorkManager instead — `emitExternalRevocation` is a fast, in-memory hand-off, but the surrounding decode + persistence is a different question.)

## Other emit triggers

RTDN→FCM is the canonical transport, but `emitExternalRevocation` is the right hook anywhere you have authoritative evidence that ownership flipped. `EntitlementCache` treats `OwnedPurchases.Live`/`Recovered` as grant-only (see [EntitlementCache](entitlement-cache.md#when-to-use-it)), so an empty owned-purchases result alone never transitions the cache to `Revoked` — you have to push the event yourself. Two common cases beyond FCM:

- **Authoritative-empty `queryPurchases` after a refresh.** If your refresh path observes a successful `queryPurchases` that returns no entitling purchases *and* your persisted cache snapshot is `Granted`, that's a refund landing without an FCM (still common — RTDN→FCM pipelines aren't always wired). Emit `PurchaseRevoked` for the cached token with `RevocationReason.Other` (or `Refunded` if your refresh path can disambiguate). Gate on the raw `queryPurchases` output, not the signature-verified subset — a verification miss means "still owned, but rejected by your verifier", not "revoked."
- **Debug `consumePurchase` flows.** A debug "consume the test purchase" button (or any test path that consumes a non-consumable) succeeds at the Play layer but leaves the cache `Granted` forever, because the cache has no signal that ownership flipped. Emit `PurchaseRevoked` for the cached token after the consume loop so the debug path exercises the same revocation transition production would see, and the cache persists as `Revoked` across cold start.

In both cases the helper is small — read your `EntitlementStorage` snapshot, no-op if null or already `Revoked`, otherwise call `emitExternalRevocation(snapshot.purchaseToken, reason)`.

`PurchaseRevoked` events route through a dedicated `replay = 16` channel — separate from `OwnedPurchases.Recovered`, so a revocation that arrives before the consumer's collector attaches isn't evicted by an empty recovery sweep. (Common case: the FCM listener decodes the payload at process start, before the UI is up.) Up to 16 revocations cached for late subscribers, sized for the realistic FCM-burst case (multi-product chargebacks resolving simultaneously, or several revocations decoded at process start). The same dedupe rule applies: a re-attached collector replays its share of the cache, so gate on `purchaseToken` if your handler isn't idempotent. Bursts beyond 16 events drop the oldest first — for guaranteed delivery of every event past that bound, persist on the consumer side before calling `emitExternalRevocation`.

## `RevocationReason` buckets

- `Refunded` — Play refunded the purchase (consumer-initiated or merchant-initiated).
- `Chargeback` — payment dispute resolved against the merchant; warrants a security-policy response on top of revocation.
- `SubscriptionExpired` — subscription has fully expired (auto-renew off **and** the paid-through period elapsed). PBL distinguishes this from `SUBSCRIPTION_CANCELED` which means auto-renew was disabled but the user still has entitlement until the period ends. Forward-compatible with v0.2.0 subscription helpers; the v0.1.x library does not emit this itself.
- `Other` — none of the above (manual revocation by support, fraud-detection action, etc.).

The library doesn't validate or inspect the reason — pick whichever bucket fits your backend's payload. The enum is there so downstream collectors get a typed switch for differentiated UX: chargeback may flag the account, a plain refund probably just shows a neutral notice.
