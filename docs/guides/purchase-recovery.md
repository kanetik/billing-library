# Purchase recovery

Play auto-refunds purchases that aren't acknowledged within 3 days. App crashes, network failures, or process death mid-acknowledge can strand a paid purchase — without recovery, the user pays, gets refunded, and never sees the entitlement.

The library handles this for you. On every fresh Play Billing connection (app start, post-disconnect reconnect, foregrounding after the connection released — the underlying connection uses `WhileSubscribed(60s)` so a quick background round-trip *doesn't* reconnect), it queries owned `INAPP` + `SUBS` purchases, filters for `PURCHASED && !isAcknowledged`, and emits any matches as `OwnedPurchases.Recovered`. Your existing `observePurchaseUpdates()` collector picks them up — no startup hook to wire, no scheduling code to write. (Exhaustive `when (event)` collectors do need a branch for `OwnedPurchases.Recovered` distinct from `OwnedPurchases.Live`; see the snippet below.)

This requires that *something* is driving the connection. The standard pattern uses `BillingConnectionLifecycleManager` (see [Lifecycle integration](lifecycle.md)), which collects `connectToBilling()` while a `LifecycleOwner` is started and triggers the recovery sweep automatically. Subscribing to `observePurchaseUpdates()` alone does **not** open the connection; pair it with the lifecycle manager (or your own `connectToBilling()` collector) so the sweep can fire. Internally the recovery channel uses `replay = 1` (see [Replay semantics](../reference/replay-semantics.md)), so a subscriber that attaches a moment after the sweep still receives the most recent recovered purchases.

The library handles `Recovered` dedupe for you. `BillingActions.handlePurchase` (and the lower-level `acknowledgePurchase` / `consumePurchase`) records the purchase token after the underlying acknowledge or consume call lands successfully against Play. The recovery channel still has `replay = 1` so the cache reflects current Play state, but `observePurchaseUpdates()` filters the cached snapshot against the acked-token set *at delivery time* (via a synchronous `map` that reads the current set per emission) — so even a late subscriber that attaches after you've already handled the purchase receives the cached sweep result re-filtered against the current acked set, not the stale pre-ack snapshot. Empty `Recovered` (intrinsic or filtered-to-empty) is dropped before delivery. There's nothing to dedupe in your collector:

```kotlin
is OwnedPurchases.Recovered -> event.purchases.forEach { purchase ->
    when (billing.handlePurchase(purchase, consume = false)) {  // or true for consumables
        HandlePurchaseResult.Success -> grantEntitlement(purchase)  // recovery is the whole point — actually grant
        HandlePurchaseResult.AlreadyAcknowledged -> {
            // Not reachable from a Recovered snapshot in practice — the sweep
            // pre-filters PURCHASED && !isAcknowledged, so the local
            // isAcknowledged flag is false and handlePurchase doesn't
            // short-circuit. Listed for exhaustiveness; the arm fires from
            // a manual queryPurchases reconciliation where you have a fresh
            // Purchase with isAcknowledged=true.
            grantEntitlement(purchase)
        }
        is HandlePurchaseResult.Failure -> {
            // Surface the error if you want, but DO NOT grant entitlement.
            // The library doesn't mark the token as acknowledged on Failure,
            // so the next sweep will surface this purchase again for retry.
        }
        HandlePurchaseResult.NotPurchased -> {}
        HandlePurchaseResult.NotOwned -> {
            // Play says this purchase isn't owned anymore (refunded, revoked,
            // already consumed). Don't grant; defer to grace/revoke logic.
        }
    }
}
```

You should still treat the `Recovered` branch idempotently if you fire other one-shot UX off it (badge animations, analytics events, etc.) — but the `Set<String>` dedupe consumers used to need against `replay = 1` re-emission is no longer required. Tracking lives for the singleton repository's lifetime (typically the process), bounded by purchase activity; a fresh sweep on reconnect re-queries Play and surfaces only genuinely-unacked tokens. (Live events on the `OwnedPurchases.Live` branch don't need this — see [Replay semantics](../reference/replay-semantics.md).)

```kotlin
billing.observePurchaseUpdates().collect { event ->
    when (event) {
        is OwnedPurchases.Live -> {
            event.purchases.forEach { handle(it) }
            fireConfetti() // user-initiated; celebrate
        }
        is OwnedPurchases.Recovered -> {
            // Same handle() call as Live — but no confetti. Background recovery,
            // not a fresh purchase. The library tracks acknowledged tokens internally
            // and suppresses replay of `Recovered` for already-handled purchases —
            // you don't need a consumer-side `Set<String>` dedupe.
            event.purchases.forEach { handle(it) }
        }
        is FlowOutcome -> { /* sub-when on Pending / Canceled / etc. */ }
    }
}
```

`Live` and `Recovered` are separate variants so you can branch your UX — don't show "thanks for your purchase!" on a sweep that ran when the user opened the app. The handle/grant code is identical for one-time products.

**Subscription replacements need special handling (until v0.2.0).** Subscription upgrade/downgrade/crossgrade purchases carry a non-null `linkedPurchaseToken` pointing at the prior subscription. Treating them as fresh grants double-grants entitlement on plan changes. PBL's `Purchase` API doesn't expose a getter for `linkedPurchaseToken` (`AccountIdentifiers` only carries `obfuscatedAccountId` / `obfuscatedProfileId`); the field is only present in `purchase.originalJson`. Until v0.2.0 ships the typed `SubscriptionReplacement` variant (see the [Roadmap](../roadmap.md)), consumers using subscriptions need to parse it themselves:

```kotlin
fun Purchase.linkedPurchaseToken(): String? = try {
    org.json.JSONObject(originalJson)
        .optString("linkedPurchaseToken")
        .takeIf { it.isNotEmpty() }
} catch (e: org.json.JSONException) { null }
```

Treat a non-null result as a plan change (invalidate the old token, grant against the new one) rather than a fresh purchase. IAP-only apps are unaffected — one-time products never carry a `linkedPurchaseToken`.

To opt out (e.g. you run a server-side reconciliation queue):

```kotlin
val billing = BillingRepositoryCreator.create(
    context = applicationContext,
    recoverPurchasesOnConnect = false
)
```
