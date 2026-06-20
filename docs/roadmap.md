# Roadmap

What's next for the Kanetik Billing Library. Items here are demand-driven — most aren't committed to until a real consumer asks. Status reflects intent at the time of last update; dates are absolute (not relative).

For what already shipped in v0.1.0, see [Design notes](design-notes.md).

---

## Versioning policy

Scope of each release tier:

- **v0.x patch / minor releases**: improvements to APIs that already exist —
  bug fixes, doc clarifications, ergonomics fixes, and small additive helpers.
  Breaking changes to fix existing API mistakes ship here too (pre-1.0 SemVer
  permits this; the alternative — sitting on a known footgun until the next
  feature cycle — is worse). Anything that makes *what's already shipped*
  better or more correct belongs in a patch / minor.
- **v0.2.0+**: net-new capability. Subscription handling. Testing artifact.
  Anything that adds a feature the library didn't previously have. The
  v0.2.0 plan below is scoped to additions, not improvements to existing
  surface.

If you're triaging an issue: "does this add a capability we didn't have?"
→ minor release. "Does this fix or improve something we already shipped?"
→ patch release.

---

## v0.1.x — optional ergonomics (RevenueCat edge-case review)

These came out of a June 2026 review of RevenueCat's ["Google Play Billing edge cases"](https://www.revenuecat.com/blog/engineering/google-play-edge-cases/) article against the shipped surface. Most of the article's edge cases are **already handled** in v0.1.x — pending-purchase partitioning, `ITEM_ALREADY_OWNED` requery, the unacknowledged-at-startup recovery sweep, acknowledge-vs-consume routing, the three-day acknowledgement cliff, disconnect/reconnect (PBL 8+ auto-reconnection plus a retry layer — fixed-delay on `SERVICE_DISCONNECTED`, exponential backoff on transient network / `SERVICE_UNAVAILABLE` — applied to both `BillingActions` operations and connection setup via `ConnectionRetryPolicy`), and retryable-vs-terminal error classification (see [Design notes](design-notes.md), [Purchase recovery](guides/purchase-recovery.md), and [Error handling](guides/error-handling.md)). A few of the article's "solutions" are now stale: enabling pending purchases and reconnection backoff were manual in its era but are first-class in PBL 8/9, which the library already opts into.

The review surfaced a few small, **optional** additive helpers worth tracking. None fixes a correctness bug in what's shipped — each just moves a currently-documented *consumer responsibility* into the library so it's harder to get wrong. All are v0.1.x-eligible (improvements to existing surface, per the policy above) and demand-driven: build when a real consumer hits the friction, not before.

### Multi-quantity `quantity` passthrough — *optional*

Today `purchase.quantity` is reachable — the `Purchase` objects ride through on `OwnedPurchases.purchases`, and the KDoc calls the field out ([Multi-quantity guide](guides/multi-quantity.md), `PurchaseEvent` / `BillingActions` KDoc) — but the library provides no dedicated accessor and never reads or applies the field itself; granting the right number of units is left entirely to the consumer. A consumer who forgets to read it silently under-grants a multi-quantity consumable to a single unit — a real footgun that only shows up once someone enables multi-quantity SKUs in the Play Console.

**Value if done:** a thin, side-effect-free accessor (a `Purchase.grantQuantity` extension, or carrying `quantity` alongside the relevant emission) makes the field impossible to miss at the grant site, turning a doc-enforced rule into a typed one. Cost is ~a one-line getter plus a test. The only argument against is that it *is* a one-line getter the consumer can write themselves. Worth it mainly once a consumer ships multi-quantity consumables.

### Standalone `linkedPurchaseToken` accessor — *optional (stopgap)*

The full fix is the typed `OwnedPurchases.SubscriptionReplacement` variant in the v0.2.0 plan below. But the README already advertises subscriptions at the protocol level in v0.1.x, and those early adopters currently have to copy-paste an `originalJson` JSON-parse snippet to read `linkedPurchaseToken` (PBL exposes no getter — see the v0.2.0 section for why).

**Value if done:** a single `Purchase.linkedPurchaseToken(): String?` extension removes that copy-paste and gives pre-v0.2.0 subscription adopters a supported way to detect plan-change replacements — and avoid double-granting entitlement — before the typed variant lands. It's a *bridge*, superseded by (not competing with) the v0.2.0 variant. Skip it entirely if no consumer does subscriptions before v0.2.0 ships.

### Order-ID accessor — *optional (low priority, likely won't do)*

The article treats order IDs as state-machine identifiers, and flags that prepaid / pending purchases have no order ID until the payment confirms. But PBL already exposes `purchase.orderId` directly on the `Purchase` the consumer holds, so there's essentially nothing to add.

**Value if done:** near-zero beyond documentation — the field is already reachable. Captured only so the review is complete. The genuinely useful related note (no order ID until a pending/prepaid payment confirms — don't gate state-machine logic on its presence) is already on the v0.2.0 pending-purchase docs list below.

---

## v0.2.0 — Subscriptions + testing artifact

Targeted after v0.1.x has shipped to two real consumers. Wakey is the first — the makebillingeasy → kanetik-billing migration has landed; on-device validation is in progress. app-revenue-tracker is queued as the second consumer once it goes freemium. Designing subs helpers without a real-app driver tends to produce bad ergonomics, so this phase waits for that signal.

**Scope discipline:** v0.2.0 is for *new capabilities only*. If a quality-of-life
or bug-fix item shows up while planning subs, it ships in a 0.1.x patch instead.

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

When a subscription replacement completes, the new `Purchase` carries a `linkedPurchaseToken` field pointing to the prior subscription. Treating that as a fresh purchase (vs a replacement of an existing one) double-grants entitlement. PBL's `Purchase` API doesn't expose a getter for it — `Purchase.AccountIdentifiers` only carries `obfuscatedAccountId` / `obfuscatedProfileId`; `linkedPurchaseToken` is only present in `purchase.originalJson`. Right now the library would emit a replacement purchase as `OwnedPurchases.Live` / `OwnedPurchases.Recovered`, leaving the consumer to parse the JSON themselves — easy to miss and clunky to write.

(RevenueCat's edge-case article — see the [v0.1.x section above](#v01x-optional-ergonomics-revenuecat-edge-case-review) — independently flags this exact double-grant trap, which validates the typed-variant approach. The standalone `Purchase.linkedPurchaseToken()` accessor noted there is the v0.1.x *stopgap* for protocol-level subscription adopters; this typed variant is the real fix and supersedes it.)

Fix: add a dedicated `OwnedPurchases` variant emitted *instead of* `Live` (or `Recovered`) whenever `linkedPurchaseToken` is non-null:

```kotlin
// Sealed-extends OwnedPurchases so it shares the existing branch
// position in `when (event)` collectors — consumers handling
// owned-state today can opt into replacement-aware logic by adding
// the new arm without rewriting their existing OwnedPurchases.Live /
// OwnedPurchases.Recovered handling.
public data class SubscriptionReplacement(
    val newPurchase: Purchase,
    val linkedPurchaseToken: String,
    override val purchases: List<Purchase> = listOf(newPurchase),
) : OwnedPurchases()
```

Detection lives in `FlowPurchasesUpdatedListener.computeUpdates`: when `responseCode == OK` and any settled purchase has a non-empty `linkedPurchaseToken` field in `originalJson`, emit `OwnedPurchases.SubscriptionReplacement` for that one (still partition the rest into `OwnedPurchases.Live` for completed-and-acknowledged-or-pending-ack purchases and `FlowOutcome.Pending` for `PURCHASE_STATE_PENDING` purchases, as today). Also emit `OwnedPurchases.SubscriptionReplacement` from the recovery sweep when it surfaces a replacement-style purchase that the prior session never processed — same JSON parsing, same conditions. Detection requires JSON-parsing `purchase.originalJson` and reading the `linkedPurchaseToken` field; PBL doesn't expose a getter (`Purchase.AccountIdentifiers` only carries the obfuscated IDs).

This makes the wrong code not type-check: you literally can't unpack the variant without seeing both tokens. The consumer's correct response is "process the new token, invalidate the old."

#### Sweep impact

The v0.1.x recovery sweep (which today emits `OwnedPurchases.Recovered`) already covers stranded subscription purchases — `queryPurchasesAsync(SUBS)` returns them and the same `PURCHASED && !isAcknowledged` filter applies. v0.2.0 needs to additionally classify recovered subscription-replacement purchases (those with `linkedPurchaseToken`) into `OwnedPurchases.SubscriptionReplacement` rather than the generic `OwnedPurchases.Recovered` variant, for the same reason the live-purchase path does.

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

## v0.3.0 candidate — Structured telemetry events (design sketched, no consumer yet)

**Status:** designed but uncommitted. No consumer has asked for this — captured here so the design isn't lost when one does, and so we don't accidentally bolt analytics onto the wrong place (e.g. the `BillingLogger` interface) in the meantime.

**Premise:** the library should never dial out. But consumers reasonably want to instrument their integration — track recovery-sweep counts in Firebase, alert on high failure rates in Crashlytics, A/B test acknowledgement timing. The right shape is *structured events the library emits, the consumer routes wherever*. Library never touches a network; consumer owns the destination, the privacy story, and the regulatory surface.

### Surface

```kotlin
public sealed class BillingTelemetryEvent {
    public data object ConnectionEstablished : BillingTelemetryEvent()
    public data class ConnectionFailed(val category: BillingErrorCategory) : BillingTelemetryEvent()

    public data class RecoverySweepRan(val unacknowledgedCount: Int) : BillingTelemetryEvent()
    public data class RecoverySweepFailed(val category: BillingErrorCategory) : BillingTelemetryEvent()

    public data class RetryAttempted(
        val operation: String,         // "queryProductDetails", "consume", etc.
        val attempt: Int,              // 1-indexed
        val category: BillingErrorCategory
    ) : BillingTelemetryEvent()

    public data class HandlePurchaseSucceeded(
        val productIds: List<String>,
        val consume: Boolean,
        val recovered: Boolean         // true if from a Recovered emission, false from Success
    ) : BillingTelemetryEvent()
    public data class HandlePurchaseFailed(
        val productIds: List<String>,
        val consume: Boolean,
        val category: BillingErrorCategory
    ) : BillingTelemetryEvent()
    public data object AcknowledgeShortCircuited : BillingTelemetryEvent()  // already-acked path

    public data class PurchaseFlowLaunched(val productId: String) : BillingTelemetryEvent()
    public data object PurchaseFlowAlreadyInProgress : BillingTelemetryEvent()
    public data object PurchaseFlowWatchdogTriggered : BillingTelemetryEvent()
}

public interface BillingTelemetryOwner {
    public fun observeTelemetry(): SharedFlow<BillingTelemetryEvent>
}
```

`BillingRepository` extends `BillingTelemetryOwner` alongside the existing `BillingActions` / `BillingConnector` / `BillingPurchaseUpdatesOwner`.

### Why a separate interface, not `BillingLogger`

`BillingLogger` is human-readable strings for logcat / Crashlytics breadcrumbs. Telemetry events are structured records for analytics pipelines. Same destination occasionally; different data shape, different consumers, different contracts. Mixing them on one interface bloats both with concerns that don't belong together.

### Privacy / data-handling rules baked into the design

- No purchase tokens — they're sensitive identifiers; the consumer can correlate via `productIds` if needed.
- No signatures or `originalJson` — same reason.
- No raw user identifiers — no `obfuscatedAccountId` / `obfuscatedProfileId` in events; the consumer already knows what it set.
- Categories, not raw response codes — emit [`BillingErrorCategory`][error-category] (the same enum the UI uses), so events line up with the rest of the library's error model.
- Product IDs are fine — developer-controlled, not PII.

[error-category]: https://github.com/kanetik/billing-library/blob/main/billing/src/main/kotlin/com/kanetik/billing/exception/BillingErrorCategory.kt

### Default behavior

`MutableSharedFlow<BillingTelemetryEvent>(extraBufferCapacity = 32)`. Always emitting; cost is one struct allocation per event plus a small buffer (~3KB worst case). Consumer subscribes via `observeTelemetry()` when they want them; otherwise events flow into the buffer and roll off. No opt-in toggle in v1 — the cost is too small to justify a configuration knob.

### Wire-up example (README addition)

```kotlin
lifecycleScope.launch {
    billing.observeTelemetry().collect { event ->
        when (event) {
            is BillingTelemetryEvent.HandlePurchaseFailed -> {
                Firebase.analytics.logEvent("billing_handle_failed") {
                    param("category", event.category.name)
                    param("consume", event.consume.toString())
                    // do NOT log productIds verbatim if they're SKU-named with PII
                }
            }
            is BillingTelemetryEvent.RecoverySweepRan ->
                Firebase.analytics.logEvent("billing_recovery") {
                    param("count", event.unacknowledgedCount.toLong())
                }
            else -> {}  // no-op for events you don't care about
        }
    }
}
```

### Out of scope for v1

- No event-type filtering at the library level — consumer does its own `filterIsInstance` / `when`.
- No timestamps in events — consumer adds them at the egress layer with its own clock.
- No sequence numbers — events are naturally ordered through the SharedFlow.
- No replay window — the recovery flow already covers "important state on connect"; telemetry is for instrumentation, not state recovery.

### Why this isn't v0.2.0

v0.2.0 is scoped to subscription handling + testing artifact (per the policy above). Telemetry doesn't fit that scope and shouldn't be jammed in. It's also worth waiting for a real consumer ask — the variant set above is my best guess at what's useful, but a consumer with a real Firebase/Mixpanel pipeline will surface gaps and excesses better than I can speculate to.

If the design above looks roughly right when a consumer asks, the implementation is small (sealed type + interface + ~10 emission sites) — under a day of work plus tests.

---

## Future (post-v0.2.0) — demand-driven

Tracked but not committed. Most are build-when-asked.

| Feature | Status | Rationale |
|---|---|---|
| Billing Choice info + dialog (PBL 9.1.0) | **shipped — experimental** | The 9.1.0 availability → info → dialog surface is wrapped as the experimental `BillingChoiceActions` (gated by `@ExperimentalBillingChoiceApi`), with `BillingChoiceClientFactory` for construction-time enablement. Thin on purpose; promote to a higher-level shape once dog-fooding shows what's needed. See [Billing Choice](guides/billing-choice.md) and [issue #39](https://github.com/kanetik/billing-library/issues/39). |
| External offers / alternative billing / user-choice *payment* flow | demand-driven | The remaining piece beyond Billing Choice's info/dialog: actually routing a purchase through an alternative payment processor via `enableUserChoiceBilling(UserChoiceBillingListener)` / `enableBillingProgram(EXTERNAL_PAYMENTS)` and handling the developer-billed selection. Region-gated (EEA + others); only worth it if a consumer needs to take external payments, not just inform users. |
| Real-Time Developer Notifications (RTDN) helpers | likely out of scope | RTDN is server-side (Cloud Pub/Sub from Play). If we add server-side helpers, they ship as a separate artifact (`com.kanetik.billing:billing-server` or similar), not folded into the client lib. README v0.1.0 documents the boundary. |
| Sub-response codes on the public error surface | demand-driven | `BillingLoggingUtils` already reads `onPurchasesUpdatedSubResponseCode` (`PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS`, `USER_INELIGIBLE`) and emits them in logs, but the wrapper's public error types don't surface them — consumers can't distinguish "insufficient funds" from any other declined purchase in their `BillingException` branch. PBL 9's [migration guide §5](https://developer.android.com/google/play/billing/migrate-gpblv9) flags handling these for "better user experience." Candidate shape: a richer `BillingException.subResponseCode` field, or a sealed sub-type under `UserCanceledException` / `DeveloperErrorException`. Patch- or minor-level work depending on shape. |
| Virtual installment subscription support | demand-driven | PBL 7+ feature, regional (Brazil, France, Italy, Spain). PBL 9's [migration guide §8](https://developer.android.com/google/play/billing/migrate-gpblv9) re-surfaces this as an optional integration alongside the v9 bump; still scoped to v0.2.0+ subscription work, not the PBL 9 dependency bump itself. Niche; build only on request. |
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
