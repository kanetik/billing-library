# Purchase recovery

If you've never shipped a Play Billing integration before, this is probably the most surprising thing about it: **a purchase isn't really yours until you acknowledge it, and Play auto-refunds anything you don't acknowledge within three days.** Not a soft warning, not a flag in the console — Play actually reverses the charge and the user loses entitlement. Crashes mid-acknowledge, network failures, the user force-quitting before your code finishes — any of those leaves a paid purchase stranded, and seventy-two hours later it's gone.

PBL doesn't help you with this. The acknowledge requirement is documented, but you're on the hook for noticing the failure and retrying. This guide is the most important read in the docs because it's the gotcha that costs real money — yours and the user's.

## What `PurchaseEvent` is, and why it has two roots

Before getting to the recovery sweep itself, a short conceptual detour. PBL gives you one callback — `PurchasesUpdatedListener` — that fires for two semantically different reasons that look identical at the API level:

- **The user actually owns a purchase** — they just completed one via your active flow, or one was already in their account from a prior session that didn't finish processing.
- **A launch attempt produced an outcome that isn't a purchase** — they canceled the dialog, the product wasn't available in their region, the network dropped, etc.

Both arrive on the same listener with a `BillingResult` and a `List<Purchase>`. The naive integration treats them the same and writes the callback's purchase list to whatever entitlement state the app maintains. But a `USER_CANCELED` outcome carries an empty (or stale) purchase list, and writing that into a cache silently corrupts your entitlement state. People have shipped real apps that revoke premium when the user opens the purchase dialog and clicks Back.

The library splits the callback into two sealed roots so the type system makes the mistake harder to make:

- **`OwnedPurchases`** — the user owns these. Acknowledge / consume / grant entitlement. Two variants:
    - `Live` — completed via the active purchase flow (or carried in an `OK` callback).
    - `Recovered` — discovered by the auto-sweep on connect (this is what the rest of this guide is about).
- **`FlowOutcome`** — describes what *happened* on a single launch attempt. Variants: `Pending` (deferred payment, e.g. cash or family approval), `Canceled`, `ItemAlreadyOwned`, `ItemUnavailable`, `Failure(BillingException)`, `UnknownResponse`. The `purchases` list on these is empty or transient. **Never write it to your entitlement cache.**

There's also `PurchaseRevoked` (a third root, sibling to the two above) for server-driven revocation events — see [Server-driven revocation](server-driven-revocation.md) for that story.

The `PurchaseEvent` marker interface deliberately doesn't expose `purchases` directly, so a `when` branch that wants to read purchases has to narrow to `OwnedPurchases` or `FlowOutcome` first. The wrong code now fails to compile rather than silently corrupting state.

## How recovery works

On every fresh Play Billing connection — app start, post-disconnect reconnect, foregrounding after the connection released — the library queries owned `INAPP` + `SUBS` purchases from Play, filters to `PURCHASED && !isAcknowledged`, and emits any matches as `OwnedPurchases.Recovered`. Your existing `observePurchaseUpdates()` collector picks them up. There's no startup hook to wire and no scheduling code to write.

(About "fresh connection": the library's `connectToBilling()` is shared via `WhileSubscribed(60s)`, so a quick background round-trip — collapsed activity, brief task switch — *doesn't* tear down and recreate the connection, and therefore *doesn't* re-run the sweep on resume. The sweep fires when the connection actually transitions from disconnected to OK. See [Replay semantics](../reference/replay-semantics.md) for the timing details.)

This requires that *something* is driving the connection. The standard pattern uses `BillingConnectionLifecycleManager` (see [Lifecycle integration](lifecycle.md)), which collects `connectToBilling()` while a `LifecycleOwner` is started and triggers the sweep automatically. Subscribing to `observePurchaseUpdates()` alone does **not** open the connection; pair it with the lifecycle manager (or your own `connectToBilling()` collector) so the sweep can fire.

The recovery channel uses `replay = 1` internally, so a subscriber that attaches a moment after the sweep still receives the most recent recovered purchases. That's important: in many apps the collector is in a ViewModel that races the connection coming up.

## Handling `Recovered` events

Same code path as `Live`. Hand each purchase to `handlePurchase` — that's the helper that performs the acknowledge or consume against Play and short-circuits if the purchase is already acknowledged.

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

You don't need a consumer-side dedupe `Set<String>`. Earlier versions of the library required one because `replay = 1` would re-deliver the cached snapshot to a re-attached collector even after the consumer had already acknowledged the purchase. The library now tracks acknowledged tokens internally (in `BillingClientStorage.acknowledgedTokens`) and filters the cached snapshot against that set at delivery time — so re-attached subscribers don't see already-handled recovered purchases.

You should still treat the `Recovered` branch idempotently if you fire other one-shot UX off it (badge animations, analytics events, etc.) — the library's dedupe is about not re-handling the *purchase*, not about coalescing your side-effects.

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

Subscription upgrade/downgrade/crossgrade purchases carry a non-null `linkedPurchaseToken` pointing at the prior subscription. If you treat one as a fresh grant rather than a replacement, you've double-granted entitlement on the plan change — the new purchase shows up in `OwnedPurchases.Live` (or `Recovered` if recovered after a crash) and looks identical to a brand-new buy.

PBL's `Purchase` API doesn't expose a getter for `linkedPurchaseToken` (`AccountIdentifiers` only carries `obfuscatedAccountId` / `obfuscatedProfileId`); the field is only present in `purchase.originalJson`. Until v0.2.0 ships the typed `OwnedPurchases.SubscriptionReplacement` variant (see the [Roadmap](../roadmap.md)), consumers using subscriptions need to parse it themselves:

```kotlin
fun Purchase.linkedPurchaseToken(): String? = try {
    org.json.JSONObject(originalJson)
        .optString("linkedPurchaseToken")
        .takeIf { it.isNotEmpty() }
} catch (e: org.json.JSONException) { null }
```

A non-null result is a plan change: invalidate the old token's grant and grant against the new one. IAP-only apps are unaffected — one-time products never carry a `linkedPurchaseToken`.

## Opting out

If you run a server-side reconciliation queue that you'd rather have own this concern:

```kotlin
val billing = BillingRepositoryCreator.create(
    context = applicationContext,
    recoverPurchasesOnConnect = false
)
```

The auto-sweep is opt-out, not opt-in, because the failure mode of skipping recovery is silent and asymmetric (paid users lose entitlement) and most apps shouldn't run without it.
