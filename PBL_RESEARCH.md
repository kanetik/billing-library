# PBL best-practices research — analysis vs. our v0.1.0

> Working notes from research across Google docs, third-party blogs, and a
> deep mining of the official `play-billing-samples` repo. Halt point — no
> code changes from this round until you triage.

## Sources scanned

| Source | Use |
|---|---|
| [PBL 8 integration guide](https://developer.android.com/google/play/billing/integrate) | (Re-)confirmed builder + offerToken + acknowledge patterns |
| [Codelab — Maximise your Play Billing integration](https://codelabs.developers.google.com/maximise-your-play-billing-integration) | Best practices Google highlights — `obfuscatedAccountId`, RTDN, Play Billing Lab |
| [PBL 7→8 migration guide](https://developer.android.com/google/play/billing/migrate-gpblv8) | Confirmed every breaking change we needed to handle |
| [Errors / response-code reference](https://developer.android.com/google/play/billing/errors) | Cross-checked our `BillingException` hierarchy |
| [Testing guide](https://developer.android.com/google/play/billing/test) | Static SKUs, license testers, Play Billing Lab |
| [BillingClient.Builder + BillingClient API ref](https://developer.android.com/reference/com/android/billingclient/api/BillingClient) | Surfaced `showInAppMessages`, `getConnectionState`, `getBillingConfigAsync` we don't expose |
| [Subscriptions guide](https://developer.android.com/google/play/billing/subscriptions) | Notes for v0.2.0 (replacement modes, multi-product subs) |
| [Distribute / play-billing](https://developer.android.com/distribute/play-billing) | Confirmed external-billing programs are out of scope |
| [Adapty blog](https://adapty.io/blog/android-in-app-purchases-with-google-play-billing-library/) | Pending-purchase + null-purchases-list edge cases |
| [RevenueCat — PBL 7 features](https://www.revenuecat.com/blog/engineering/google-play-billing-library-7-features-migration/) | Order-ID timing for pending purchases (v0.2.0 docs) |
| Medium article (Sivavishnu) | **Behind paywall — couldn't extract content** |
| Local clone: `../play-billing-samples` (`purchases`, `managedcatalogue`, `subscriptions`) | Side-by-side comparison with our library |

## Validated — we already match (or beat) the canonical patterns

| Area | Our impl | Notes |
|---|---|---|
| `BillingClient` builder | `enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts())` + `enableAutoServiceReconnection()` + `setListener` | Matches PBL 8.x integration guide exactly |
| All PBL 8.x removed APIs | We use none of them (no `querySkuDetailsAsync`, no `queryPurchaseHistoryAsync`, no no-arg `enablePendingPurchases`, no string-typed `queryPurchasesAsync`) | ✓ |
| `BillingResponseCode` coverage | 11 typed `BillingException` subtypes + `UnknownException` fallback. Every retriable code has correct `RetryType` | Cross-checked the errors-reference table; full coverage |
| `oneTimePurchaseOfferDetailsList.firstOrNull()?.offerToken` | Default selector picks first; consumers can override via lambda | The Google samples (`managedcatalogue`) actually iterate offers and detect rental vs. buy — we support that pattern via the lambda |
| Pending-purchase splitting | `PurchasesUpdate.Pending` is its own sealed variant; `FlowPurchasesUpdatedListener` partitions by `Purchase.PurchaseState` | **We do this MORE correctly than Google's `purchases` sample**, which incorrectly acknowledges PENDING purchases |
| Null `purchases` from PBL callback | Handled via `purchases.orEmpty()` in `FlowPurchasesUpdatedListener` | Adapty flagged this as a non-obvious edge case; we cover it |
| Activity validation before launch | `validatePurchaseActivity()` (`isFinishing`/`isDestroyed` + RESUMED gate) | Google samples don't do this at all |
| Connection lifecycle | Lazy hot SharedFlow with 60s `WhileSubscribed` grace window + PBL 8 auto-reconnect | Google samples are manual `startConnection`/`endConnection` per Activity — our shared/lazy approach is more correct for production |
| In-flight guard + watchdog | `PurchaseFlowCoordinator` has `AtomicBoolean` + 2-min watchdog + correlation-id logging | Not present in any Google sample; widely-recommended pattern in third-party blogs |
| Signature verification | `PurchaseVerifier` with pluggable `signatureAlgorithm` | Google samples defer this entirely to backend; we ship a working client-side option |
| Dispatcher strategy | Split `ioDispatcher` (queries, retry loop) + `uiDispatcher` (`launchFlow` only) | Google samples leave threading implicit — we're explicit |
| `obfuscatedAccountId` for fraud | `toOneTimeFlowParams(obfuscatedAccountId = ...)` | Codelab calls this out as recommended |
| Acknowledge convenience overload | `acknowledgePurchase(purchase)` short-circuits if `isAcknowledged` | Per Q2 from the prior round; matches Google's explicit "check `isAcknowledged()` first" guidance |

## Proposed additions / improvements

Grouped by impact. Most are small; a couple genuinely expand the API surface.

### 🟡 Strong — worth adding for v0.1.0

#### A. `consumePurchase(purchase: Purchase)` convenience overload

**Why**: We added `acknowledgePurchase(purchase)` per Q2 last round. The
parallel doesn't exist for `consumePurchase` — consumers must build
`ConsumeParams` manually each time.

```kotlin
@AnyThread
public suspend fun consumePurchase(purchase: Purchase): String {
    val params = ConsumeParams.newBuilder()
        .setPurchaseToken(purchase.purchaseToken)
        .build()
    return consumePurchase(params)
}
```

Default-impl on `BillingActions` interface. Symmetry with the existing
acknowledge overload.

#### B. Top-level `BillingActions.showInAppMessages(activity, params)` exposure

**Why**: PBL 8 supports `BillingClient.showInAppMessages()` for overlaying
billing-related UI (e.g. "your subscription payment was declined, fix
your payment method"). It's a one-line PBL call, useful for both one-time
and subscription flows. We don't expose it — users would have to drop
down to the raw `BillingClient`, which is internal in our design.

```kotlin
@MainThread
public suspend fun showInAppMessages(
    activity: Activity,
    params: InAppMessageParams
): InAppMessageResult
```

Returns a typed result wrapping `InAppMessageResponseListener.onInAppMessageResponse`.
Cleanest if we add a small `InAppMessageResult` wrapper analogous to
`PurchasesUpdate` so we don't leak PBL's `InAppMessageResult` directly.

Worth deciding: scope this for v0.1.0 or defer to v0.2.0? My lean: **add now**.
It's small, it's a real PBL feature consumers expect, and skipping it
forces a workaround.

#### C. KDoc warning on `PurchasesUpdate.Pending`

**Why**: Google's own `purchases` sample gets this *wrong* — it
acknowledges `PENDING` purchases. Our sealed variant gets the
data-modeling right but the KDoc could spell out the cardinal rule more
forcefully.

```kotlin
public data class Pending(override val purchases: List<Purchase>) : PurchasesUpdate() {
    // CARDINAL RULE: do NOT acknowledge / consume / grant entitlement on a
    // Pending update. Wait for the matching Success update that arrives
    // when the deferred payment confirms (cash payment, etc.). Granting
    // entitlement on Pending is the most common bug in PBL integrations
    // — even Google's own samples got this wrong.
}
```

#### D. `connectionState` query on `BillingConnector`

**Why**: Right now consumers can collect `connectToBilling()` to observe
the flow but can't peek at the *current* state synchronously. PBL exposes
`BillingClient.getConnectionState()` (returns `OK`/`CONNECTING`/`DISCONNECTED`/`DISCONNECTING`).
For consumers building UI ("show 'connecting' indicator"), this would
help.

But we already return `SharedFlow<BillingConnectionResult>` from
`connectToBilling()`, and consumers can do `flow.replayCache.firstOrNull()`
to peek. Adding a separate `connectionState` accessor is redundant *unless*
we want to surface PBL's CONNECTING / DISCONNECTING transient states,
which our current model collapses to "Success or Error".

**My lean: skip**. The SharedFlow + replay-1 already serves the use case;
adding a second API for the same info is bloat. Mention in README's
"advanced usage" section.

### 🟢 Nice-to-have — README/docs work for Phase 4

| Topic | What to document |
|---|---|
| Play Billing Lab | New testing tool from Google — overrides for region, real payment methods, accelerated subscription periods. README's testing section should mention it alongside static test SKUs. |
| Pre-order offer selection | Show a `toOneTimeFlowParams(offerSelector = { ... })` example for picking pre-order offers explicitly, since `managedcatalogue` is the only Google sample that exercises this. |
| Connection grace window | The 60s `WhileSubscribed` timeout is opaque. Document it as a deliberate design choice (avoids reconnection churn) so consumers don't wonder why the connection stays warm after their last call. |
| Product-details caching strategy | We don't cache; consumers wrap our suspend `queryProductDetails` in a `StateFlow` if they want session-level caching. README should call this out as the recommended pattern. |
| Signature verification integration | Show the recommended pattern: collect `observePurchaseUpdates()` → on each `Success`, run `purchaseVerifier.isSignatureValid()` → reject or acknowledge. Currently `PurchaseVerifier` is orphaned — consumers may not realize they need to wire it in. |

### 🤔 Considered but not adopted

#### `ConnectionTimeoutException` as a dedicated exception subtype

The Explore agent suggested adding this as a separate type. Currently the
30s `withTimeout` in `connectToClientAndCall` throws via
`BillingException.fromResult(timeoutResult)` where `timeoutResult` carries
`SERVICE_UNAVAILABLE` — so it surfaces as `ServiceUnavailableException`,
not `UnknownException` (the agent was slightly off on this). And
semantically, "connection didn't establish in time" *is* a service-
unavailable condition — the existing typed exception is the right
category. Adding a new subclass would make the hierarchy more granular
without giving consumers a meaningful new branching opportunity.

**Skip.** The current behavior is semantically correct; the agent's
concern was based on a misread of which subtype gets thrown.

#### `getBillingConfigAsync`

Rarely needed; useful for region-specific UX customization but a
specialized concern. Defer.

#### `getConnectionState()` direct accessor

See D above. Redundant given the SharedFlow+replay-1 access pattern.

#### Server-side / RTDN integration

Out of scope for a client library. Document as such in README limitations.

#### Alternative billing / user-choice billing / external offers

Already documented as out-of-scope in README Limitations.

#### `BillingActions.handlePurchase(purchase, isConsumable)` higher-level helper

The Explore agent suggested baking the consume-vs-acknowledge decision
into the library. I disagree — *which* products are consumable vs. not
is fundamentally an app concern (it depends on Play Console product
configuration + the app's own logic). Forcing consumers to pass an
`isConsumable` lambda just shifts the same decision into our API surface
without adding value. Keep `acknowledgePurchase` and `consumePurchase`
as separate first-class methods; document the typical pattern in README.

## v0.2.0 prep notes (capture for `docs/ROADMAP.md` later)

Worth recording now while the research is fresh:

- **Subscription replacement mode**: PBL 8.1.0 deprecated
  `SubscriptionUpdateParams.setSubscriptionReplacementMode` in favor of
  per-line-item `SubscriptionProductReplacementParams.setReplacementMode`.
  v0.2.0 subs helpers must use the new API.
- **DEFERRED replacement mode behavior change**: PBL 6+ changed it so the
  new purchase token is delivered *immediately* (not at renewal). Our
  v0.2.0 helpers must process the new token right away.
- **Multi-product / add-on subscriptions**: PBL 8 supports replacing
  multiple line items in one flow. Worth a dedicated helper.
- **Pending purchases for prepaid plans (PBL 7+)**: order ID isn't
  generated until the pending payment confirms. Don't rely on order ID
  presence for state-machine logic.
- **Virtual installment subscriptions (PBL 7+)**: niche
  (Brazil/France/Italy/Spain only). Skip unless asked.
- **Real-time Developer Notifications (RTDN)**: server-side concern, but
  worth a README pointer so consumers know our library only covers the
  client side.

## Recommended triage order

If you want to apply any of this:

1. **A** (`consumePurchase(Purchase)` overload) — 5 minutes, pure symmetry win.
2. **C** (`PurchasesUpdate.Pending` cardinal-rule KDoc) — trivial doc edit.
3. **B** (`showInAppMessages` exposure) — meaningful API addition. Worth
   a separate commit; needs a small `InAppMessageResult` wrapper to avoid
   leaking PBL's type. Quote me a yes/no on whether you want this in v0.1.0.
4. **🟢 docs items** — capture for Phase 4 README work; don't apply now.
5. **🤔 considered-but-skipped** — record decision in CHANGELOG only if
   asked.

This file gets deleted once you've triaged. Reply with your picks (e.g.
`A: yes, B: yes, C: yes, others: defer`) and I'll roll the inline ones
into focused commits before continuing Phase 2.7 (tests).
