# Purchase recovery

If you've never shipped a Play Billing integration before, this is probably the most surprising thing about it: a purchase isn't really yours until you acknowledge it, and Play auto-refunds anything you don't acknowledge within three days. That's not a soft warning or a console flag. Play reverses the charge, the user loses entitlement, and the only signal you get is a refund showing up in their order history. Mid-acknowledge crashes, dropped network requests, force-quits before your code finishes: any of these strand a paid purchase, and seventy-two hours later it's gone.

PBL doesn't help. The requirement is documented, but noticing failures and retrying is on you. That's why this is probably the most important page in these docs: the failure mode is silent and asymmetric, and it costs real money on both sides.

## What `PurchaseEvent` is, and why it has two roots

PBL gives you one callback, `PurchasesUpdatedListener`, that fires for two semantically different reasons that look identical at the API level:

- The user actually owns a purchase — they just completed one through your active flow, or one was already on their account from a prior session that didn't finish processing.
- A launch attempt produced an outcome that *isn't* a purchase — they canceled the dialog, the product wasn't available in their region, the network dropped.

Both arrive on the same listener with a `BillingResult` and a `List<Purchase>`. A naive integration treats them the same and writes the callback's purchase list to whatever entitlement state the app keeps. But a `USER_CANCELED` outcome carries an empty (or stale) purchase list, and writing that into a cache silently corrupts state. People have shipped real apps that revoke premium when the user taps Back on the purchase dialog.

The library splits the callback into two sealed roots so the type system can catch this at compile time:

- `OwnedPurchases` — the user owns these. Acknowledge / consume / grant entitlement. Two variants:
    - `Live` — completed through the active purchase flow (or carried in an `OK` callback).
    - `Recovered` — found by the auto-sweep on connect (the rest of this guide is about these).
- `FlowOutcome` — describes what *happened* on a single launch attempt. Variants: `Pending` (deferred payment, e.g. cash or family approval), `Canceled`, `ItemAlreadyOwned`, `ItemUnavailable`, `Failure(BillingException)`, `UnknownResponse`. The `purchases` list on these is empty or transient. Never write it to your entitlement cache.

There's also `PurchaseRevoked`, a third root sibling to the two above, for server-driven revocation events. See [Server-driven revocation](server-driven-revocation.md) for that story.

The `PurchaseEvent` marker interface doesn't expose `purchases` at all, so a `when` branch that wants to read purchases has to narrow to `OwnedPurchases` or `FlowOutcome` first. The wrong code fails to compile instead of silently corrupting state.

## How recovery works

On every fresh Play Billing connection (app start, post-disconnect reconnect, foregrounding after the connection released) the library queries owned `INAPP` + `SUBS` purchases from Play, filters to `PURCHASED && !isAcknowledged`, and emits any matches as `OwnedPurchases.Recovered`. Your existing `observePurchaseUpdates()` collector picks them up. There's no startup hook to wire and no scheduling code to write.

A quick aside on "fresh connection": the library shares `connectToBilling()` via `WhileSubscribed(60s)`, so a brief background round-trip (a collapsed activity, a quick task switch) doesn't tear down and recreate the connection, and so doesn't re-run the sweep on resume. The sweep fires when the connection genuinely transitions from disconnected to OK. See [Replay semantics](../reference/replay-semantics.md) for the timing details.

This requires that *something* is driving the connection. The standard pattern uses `BillingConnectionLifecycleManager` (see [Lifecycle integration](lifecycle.md)), which collects `connectToBilling()` while a `LifecycleOwner` is started and triggers the sweep automatically. Subscribing to `observePurchaseUpdates()` alone does **not** open the connection; pair it with the lifecycle manager (or your own `connectToBilling()` collector) so the sweep can fire.

The recovery channel uses `replay = 1` internally, so a subscriber that attaches a moment after the sweep still receives the most recent recovered purchases. That's important: in many apps the collector is in a ViewModel that races the connection coming up.

## Handling `Recovered` events

Same code path as `Live`. Hand each purchase to `handlePurchase`, the helper that performs the acknowledge or consume against Play and short-circuits if the purchase is already acknowledged.

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

You don't need a consumer-side dedupe `Set<String>`. Earlier versions of the library required one because `replay = 1` would re-deliver the cached snapshot to a re-attached collector even after the consumer had already acknowledged the purchase. The library now tracks acknowledged tokens internally (in `BillingClientStorage.acknowledgedTokens`) and filters the cached snapshot against that set at delivery time, so re-attached subscribers don't see already-handled recovered purchases.

You should still treat the `Recovered` branch idempotently if you fire other one-shot UX off it (badge animations, analytics events, etc.). The library's dedupe is about not re-handling the *purchase*, not about coalescing your side-effects.

## Live vs. Recovered: when the UX should differ

`Live` and `Recovered` are separate variants so you can branch your UX. Don't show "thanks for your purchase!" on a sweep that ran when the user opened the app. The handle/grant code is identical for one-time products; only the surrounding UX differs.

```kotlin
billing.observePurchaseUpdates().collect { event ->
    when (event) {
        is OwnedPurchases.Live -> {
            event.purchases.forEach { handle(it) }
            fireConfetti() // user-initiated; celebrate
        }
        is OwnedPurchases.Recovered -> {
            // Same handle() call as Live — but no confetti. Background
            // recovery, not a fresh purchase.
            event.purchases.forEach { handle(it) }
        }
        is FlowOutcome -> { /* sub-when on Pending / Canceled / etc. */ }
    }
}
```

## Subscription replacements need extra care (until v0.2.0)

Subscription upgrade/downgrade/crossgrade purchases carry a non-null `linkedPurchaseToken` pointing at the prior subscription. If you treat one as a fresh grant rather than a replacement, you've double-granted entitlement on the plan change. The new purchase shows up in `OwnedPurchases.Live` (or `Recovered` if recovered after a crash) and looks identical to a brand-new buy.

PBL's `Purchase` API doesn't expose a getter for `linkedPurchaseToken` (`AccountIdentifiers` only carries `obfuscatedAccountId` / `obfuscatedProfileId`); the field is only present in `purchase.originalJson`. Until v0.2.0 ships the typed `OwnedPurchases.SubscriptionReplacement` variant (see the [Roadmap](../roadmap.md)), consumers using subscriptions need to parse it themselves:

```kotlin
fun Purchase.linkedPurchaseToken(): String? = try {
    org.json.JSONObject(originalJson)
        .optString("linkedPurchaseToken")
        .takeIf { it.isNotEmpty() }
} catch (e: org.json.JSONException) { null }
```

A non-null result is a plan change: invalidate the old token's grant and grant against the new one. IAP-only apps are unaffected; one-time products never carry a `linkedPurchaseToken`.

## Opting out

If you run a server-side reconciliation queue and you'd rather have it own this concern:

```kotlin
val billing = BillingRepositoryCreator.create(
    context = applicationContext,
    recoverPurchasesOnConnect = false
)
```

The default is on. Skipping recovery without a server-side replacement quietly costs paid users their entitlement, so it's not the kind of thing to disable casually.
