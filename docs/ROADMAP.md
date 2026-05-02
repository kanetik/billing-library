# Roadmap

What's next for the Kanetik Billing Library. Items here are demand-driven — most aren't committed to until a real consumer asks. Status reflects intent at the time of last update; dates are absolute (not relative).

For what already shipped in v0.1.0, see [`BUILD_HISTORY.md`](BUILD_HISTORY.md).

---

## v0.2.0 — Subscriptions + testing artifact

Targeted after v0.1.0 has shipped to two real consumers. Wakey is the first — the makebillingeasy → kanetik-billing migration has landed; on-device validation is in progress. app-revenue-tracker is queued as the second consumer once it goes freemium. Designing subs helpers without a real-app driver tends to produce bad ergonomics, so this phase explicitly waits for that signal.

### Subscription helpers — *planned*

PBL 8.x specifics the helpers must respect:

- **Replacement modes** use `SubscriptionProductReplacementParams.setReplacementMode` per-line-item. The older `SubscriptionUpdateParams.setSubscriptionReplacementMode` was deprecated in PBL 8.1.
- **DEFERRED replacement** delivers the new purchase token *immediately* (PBL 6+ behavior change), not at renewal. Helpers must process the new token right away rather than waiting.
- **Multi-product / add-on subscriptions** — PBL 8 supports replacing multiple line items in one flow via per-line-item `SubscriptionProductReplacementParams`. Worth a dedicated helper.
- **Subscription offer-token selection** walks `subscriptionOfferDetails` (base plans + offers); pick by `offerId`, base-plan-id, or a custom selector. Different shape from one-time products.
- **Pending purchases for prepaid plans (PBL 7+)**: order ID isn't generated until pending payment confirms. Don't rely on order-ID presence for state-machine logic.
- **Six replacement modes** — `CHARGE_FULL_PRICE`, `CHARGE_PRORATED_PRICE`, `WITH_TIME_PRORATION`, `WITHOUT_PRORATION`, `DEFERRED`, `KEEP_EXISTING` (last is for add-ons / partial replacement).

Helpers to design:

- `ProductDetails.selectDefaultBasePlan()` extension
- `ProductDetails.selectOffer(offerId)` / `(predicate: (SubscriptionOfferDetails) -> Boolean)` extensions
- `ProductDetails.toSubscriptionFlowParams(offerToken, obfuscatedAccountId?, replacement?)` extension
- `SubscriptionLineItemUpdate(oldProductId, newProductDetails, offerToken, replacementMode)` for multi-line-item flows
- Subs sample variant in `/sample`
- Subscription upgrade/downgrade docs page in `/docs/`
- README subs note loses the "not yet" disclaimer

#### Replacement-mode API: name the intent, not the mode

Don't expose PBL's six raw replacement-mode integers directly — the names tell you *what proration math runs*, not *what plan change you're making*. Wrap intent in a sealed type so callers say what they mean and the helper picks the right PBL mode internally:

```kotlin
public sealed class SubscriptionChange {
    public data class Upgrade(val from: Purchase, val to: ProductDetails, val offerToken: String) : SubscriptionChange()
    public data class Downgrade(val from: Purchase, val to: ProductDetails, val offerToken: String) : SubscriptionChange()
    public data class CrossGrade(val from: Purchase, val to: ProductDetails, val offerToken: String) : SubscriptionChange()
    public data class AddOn(val existing: Purchase, val addition: ProductDetails, val offerToken: String) : SubscriptionChange()
}
```

Mapping the helper applies internally:

| Intent | PBL mode | Why |
|---|---|---|
| `Upgrade` | `CHARGE_PRORATED_PRICE` | Charges immediately for the upgrade delta; new tier active right away. |
| `Downgrade` | `DEFERRED` | New tier activates at next renewal; user keeps current benefits through the paid period. |
| `CrossGrade` | `WITHOUT_PRORATION` | Same price tier, no money math; instantaneous swap. |
| `AddOn` | `KEEP_EXISTING` | Adds a line item without disturbing the existing one. |

Consumers who genuinely need a non-default mode can drop down to a lower-level `toSubscriptionFlowParams(replacementMode = ...)` overload. The sealed-type API is the front door; the raw mode is the escape hatch.

#### linkedPurchaseToken: surface as a typed sealed variant

When a subscription replacement completes, the new `Purchase` has a non-null `linkedPurchaseToken` pointing to the prior subscription. Treating that as a fresh purchase (vs a replacement of an existing one) double-grants entitlement. Right now the library would emit it as `PurchasesUpdate.Success`, leaving the consumer to dig into `purchase.accountIdentifiers?.linkedPurchaseToken` themselves — easy to miss.

Fix: add a dedicated variant emitted *instead of* `Success` whenever `linkedPurchaseToken` is non-null:

```kotlin
public data class SubscriptionReplacement(
    val newPurchase: Purchase,
    val linkedPurchaseToken: String,
    override val purchases: List<Purchase> = listOf(newPurchase)
) : PurchasesUpdate()
```

Detection lives in `FlowPurchasesUpdatedListener.computeUpdates`: when `responseCode == OK` and any settled purchase has `linkedPurchaseToken != null`, emit a `SubscriptionReplacement` for that one (still partition the rest into `Success` / `Pending` as today). Also emit `SubscriptionReplacement` from the recovery sweep when it surfaces a replacement-style purchase that the prior session never processed.

This makes the wrong code not type-check: you literally can't unpack the variant without seeing both tokens. The consumer's correct response is "process the new token, invalidate the old."

#### Sweep impact

The v0.1.0 recovery sweep (`PurchasesUpdate.Recovered`) already covers stranded subscription purchases — `queryPurchasesAsync(SUBS)` returns them and the same `PURCHASED && !isAcknowledged` filter applies. v0.2.0 needs to additionally classify recovered subscription-replacement purchases (those with `linkedPurchaseToken`) into `SubscriptionReplacement` rather than the generic `Recovered` variant, for the same reason the live-purchase path does.

### `:billing-testing` artifact — *planned*

New Gradle module published as `com.kanetik.billing:billing-testing:0.2.0`:

- `FakeBillingRepository` — in-memory `BillingRepository` for unit tests + debug-flavor DI overrides
- Test-control API: `setConnectionResult`, `emitPurchaseUpdate`, `setProducts`, `setPurchases`, `throwOnNext(BillingException)`, `simulateLaunchFlowResult`
- Robolectric included → unblocks the four classes deferred from v0.1.0's test suite:
  - `PurchaseVerifier` (needs `android.util.Base64`)
  - `ProductDetails.toOneTimeFlowParams` (needs real PBL `BillingFlowParams.Builder` to satisfy validation)
  - `DefaultBillingRepository` orchestration (retry loop, `withTimeout`, `launchFlow` error wrapping, `queryProductDetailsWithUnfetched` mapping — needs Robolectric's looper for proper dispatcher behavior + the real PBL classes)
  - `showInAppMessages` (needs the real `InAppMessageResult` shape)
- Unit tests covering every state transition the fake claims to support
- README section "Testing with FakeBillingRepository" with a JUnit example + Hilt debug-flavor DI example
- Adopt in Wakey's debug flavor where `DebugConfig.mockUserType` currently short-circuits the real billing repo — gives the real plumbing coverage even in mocked-entitlement debug builds

### app-revenue-tracker adoption — *planned*

Second real consumer once that app goes freemium. Almost certainly surfaces design issues that Wakey didn't, since the use cases differ (tracker is metric-driven; Wakey is feature-gating).

### v0.2.0 docs additions — *planned*

- Pending-purchase docs: order-ID timing for prepaid plans (no order ID until completion)
- Subscription replacement-mode quick-reference table
- `:billing-testing` integration patterns
- Pre-order full-flow example (PBL 8.1+ `OneTimePurchaseOfferDetails.preorderDetails`)

---

## Future (post-v0.2.0) — demand-driven

Tracked but not committed. Most are build-when-asked.

| Feature | Status | Rationale |
|---|---|---|
| External offers / alternative billing / user-choice billing | demand-driven | PBL 8 added external-billing programs. Requires extending `BillingClientFactory` (or adding a parallel factory) to call `enableUserChoiceBilling` / `enableBillingProgram`. Region-gated (EEA + others); only worth it if a consumer needs it. |
| Real-Time Developer Notifications (RTDN) helpers | likely out of scope | RTDN is fundamentally server-side (Cloud Pub/Sub from Play). If we add server-side helpers, they ship as a separate artifact (`com.kanetik.billing:billing-server` or similar), not folded into the client lib. README v0.1.0 documents the boundary. |
| Virtual installment subscription support | demand-driven | PBL 7+ feature, regional (Brazil, France, Italy, Spain). Niche but exists; build only on request. |
| Subscription pause / resume / migrate APIs | demand-driven | PBL exposes these; we can layer helpers when a consumer needs them. |
| Promo code redemption flow helpers | demand-driven | Play handles redemption; library could surface redemption events more cleanly. Niche. |
| Pre-order full lifecycle helpers | demand-driven | PBL 8.1+ `OneTimePurchaseOfferDetails.preorderDetails`. v0.2.0 docs cover the manual path; helpers are post-v0.2.0. |
| `getBillingConfigAsync` exposure | demand-driven | Region-specific UX customization. Niche; defer until requested. |
| Connection grace-window tunable | demand-driven | The 60s `WhileSubscribed` is currently hardcoded. Could surface as a creator parameter if a consumer needs different timing. |
| `getConnectionState()` direct accessor | demand-driven | PBL exposes `ConnectionState` (OK / CONNECTING / DISCONNECTED / DISCONNECTING). Our `SharedFlow<BillingConnectionResult>` collapses to Success/Error. If consumers want to render "connecting…" UI, expose the finer state. |
| Java consumer interop pass (`@JvmStatic`, `@JvmOverloads`, etc.) | rejected for now | Skipped per Kotlin-first decision; revisit only if Java consumers complain. |
| ABI stability tooling (binary-compatibility-validator) | scheduled for ~1.0 | Approaching 1.0: integrate the plugin to track public-ABI changes between versions. |
| Compose-aware lifecycle helpers | demand-driven | If a consumer wants `rememberBillingRepository(...)` Compose-side affordances, build then. |

---

## How items get added

Open an issue at <https://github.com/kanetik/billing-library/issues> with:

1. **Use case** — what app are you building, and what's the friction with the current API?
2. **Concrete proposal** — API shape you'd want, even if rough. Code samples beat prose.
3. **Willingness to test** — first consumer of a new feature gets weight in the design.

Issues with a clear use case + a real consumer almost always make it onto this roadmap. Pure speculation without a consumer typically doesn't.
