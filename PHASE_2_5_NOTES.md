# Phase 2.5 — Google PBL 8 validation notes

> **Status: AI first-pass validation complete. HALTED for your review.**
> Working file produced per the plan's Phase 2.5b checkpoint. After you've
> done your own pass and weighed in on the open questions below, this file
> should be deleted (its decisions migrate into commit messages, KDoc, and
> CHANGELOG entries).

## Sources scanned

| Source | Use |
|---|---|
| [PBL 8 integration guide](https://developer.android.com/google/play/billing/integrate) | Construction, query, launchFlow, acknowledge/consume patterns |
| [PBL release notes](https://developer.android.com/google/play/billing/release-notes) | What changed in 8.x (8.0–8.3) |
| [BillingClient.Builder reference](https://developer.android.com/reference/com/android/billingclient/api/BillingClient.Builder) | Available builder methods + recommended config |

**Note**: `github.com/android/play-billing-samples` returned **404** when
queried — Google's canonical PBL samples repo appears to have been
archived/removed. This eliminated one cross-check the plan called for.
Forks exist (`Catappult/google-play-billing-samples`, etc.) but they're not
canonical. The integration guide itself is authoritative for v0.1.0.

## Validated — matches Google's recommended PBL 8 patterns

| Area | Our impl | Google's pattern | Verdict |
|---|---|---|---|
| `BillingClient.Builder` chain | `newBuilder(context).enablePendingPurchases(...enableOneTimeProducts()...).enableAutoServiceReconnection().setListener(listener).build()` (`DefaultBillingClientFactory.kt`) | Same chain, with `enablePendingPurchases(PendingPurchasesParams)` (the no-arg overload was **removed** in 8.0.0 — we use the params form) | ✅ |
| `enableAutoServiceReconnection` interaction with retry | `RetryType.SIMPLE_RETRY` on `ServiceDisconnectedException` | Google explicitly says auto-reconnect "does NOT eliminate app-side retry" — short retry gives reconnection time to land | ✅ Correctly retained |
| One-time `setOfferToken` requirement | `ProductDetails.toOneTimeFlowParams()` always sets `setOfferToken(oneTimePurchaseOfferDetailsList.firstOrNull()?.offerToken)` when present | "`setOfferToken()` is required" for one-time products under PBL 8 | ✅ — but see Open Q1 about multi-offer products |
| Sub-response codes | `BillingLoggingUtils.getSubResponseCodeDescription` handles all 3 (`PAYMENT_DECLINED_DUE_TO_INSUFFICIENT_FUNDS`, `USER_INELIGIBLE`, `NO_APPLICABLE_SUB_RESPONSE_CODE`) | Same 3 codes; no new ones added in 8.1–8.3 | ✅ |
| `BillingResponseCode` coverage | 12 `BillingException` subtypes covering every PBL response code + Unknown fallback | No new codes added in 8.x | ✅ |
| 3-day acknowledgement window | KDoc on `BillingActions.acknowledgePurchase` calls it out | Unchanged since PBL 2.0 (2019) | ✅ |
| `queryPurchaseHistory()` / `querySkuDetailsAsync()` removal | Library doesn't use either | Both removed in PBL 8.0.0 | ✅ |
| `queryProductDetailsWithUnfetched` rationale | Wraps callback API directly so we can return Google's `QueryProductDetailsResult` (with unfetched list) instead of legacy `ProductDetailsResult` | The billing-ktx `queryProductDetails` suspend extension returns the legacy type; manual bridge is the correct workaround | ✅ |

## Clear-cut fixes applied during this pass

### `minSdk` 21 → 23 — PBL 8.1.0 raised the floor

The plan locked `minSdk = 21` with the rationale "matches PBL 8 floor", but
that assumption was based on PBL 8.0.0 which kept the 21 floor. PBL **8.1.0
raised** `minSdkVersion` to **23** (Android 6.0). We pin to PBL 8.3.0, so 23
is the actual floor.

Going lower than 23 would let API 21–22 device users install the consumer
app, then hit a runtime crash inside the billing client. Worse than just
losing those users.

API 21–22 (Lollipop) is ~0.1% of active Android devices in 2026 — no
material loss.

**Applied**: `billing/build.gradle.kts` `minSdk = 23` with explanatory comment.
Verified `:billing:assembleDebug` still succeeds.

## Open questions for your judgment

### Q1: Multi-offer one-time products — should `toOneTimeFlowParams` accept an offer selector?

**Background**: PBL 8.0.0 introduced support for one-time products with
*multiple purchase options and offers* (e.g. pre-orders, alternative price
tiers). PBL 8.1.0 added `getPreorderDetails()` on those offers.

Our `ProductDetails.toOneTimeFlowParams()` always calls
`oneTimePurchaseOfferDetailsList.firstOrNull()?.offerToken` — picks the
first available offer.

For products configured with **one** offer (the auto-migrated default that
covers ~100% of existing apps), this is correct. For products configured
with multiple offers, our helper silently picks the first — which may not
match the developer's intent (e.g. a pre-order flow).

**Options**:
- **(a)** Keep as-is for v0.1.0. Document the single-offer assumption in
  KDoc. Add multi-offer support in v0.2.0.
- **(b)** Add an overload taking `offerSelector: (List<OneTimePurchaseOfferDetails>) -> OneTimePurchaseOfferDetails?`
  in v0.1.0 so the developer can pick. Default selector picks first.
- **(c)** Defer the whole `toOneTimeFlowParams` helper to v0.2.0; v0.1.0
  consumers build flow params manually.

**My lean: (a)**. The current behavior is right for the dominant case; the
limitation is well-bounded; v0.2.0 can extend without breaking v0.1.0
callers. Adding the selector lambda in (b) is fine but front-loads
ergonomic complexity for a feature we have no v0.1.0 consumer for.

### Q2: Pre-acknowledge `isAcknowledged()` short-circuit?

**Background**: Google's integration guide says: *"Before acknowledging a
purchase, your app should check whether it was already acknowledged by
using the `isAcknowledged()` method."*

Our `BillingActions.acknowledgePurchase(params)` is a thin wrapper — it
delegates straight to PBL with no pre-check. Calling
`acknowledgePurchase` on an already-acknowledged purchase produces a
`DEVELOPER_ERROR` from Play.

**Options**:
- **(a)** Keep the wrapper thin. Document in KDoc that consumers must
  check `purchase.isAcknowledged` first. (Current state.)
- **(b)** Add a convenience overload `acknowledgePurchase(purchase: Purchase)`
  that pre-checks `purchase.isAcknowledged` and short-circuits if already
  acknowledged. Existing param-based overload stays.
- **(c)** Change the existing overload's behavior to silently no-op on
  already-acknowledged. (Breaking change to existing semantics — probably
  bad.)

**My lean: (b)**. The convenience overload is genuinely useful, hard to
get wrong, and matches Google's guidance verbatim. The param-based
overload stays for power users.

### Q3: Subscription replacement mode — note for v0.2.0

PBL 8.1.0 deprecated `SubscriptionUpdateParams.setSubscriptionReplacementMode`
in favor of `SubscriptionProductReplacementParams.setReplacementMode`.

No v0.1.0 action — we don't ship subscription helpers. Just **remember to
use the new API** when v0.2.0 subs helpers land.

### Q4: External offers / alternative billing — out of scope

PBL 8.0+ supports external offers via `enableBillingProgram`. PBL 8.2.0
deprecated the old `enableExternalOffer` family.

Supporting this requires extending `BillingClientFactory` so consumers can
configure the program. **Out of scope for v0.1.0**. Worth flagging in
README's "what this library doesn't do" note? Or just leave silent and
let users provide their own `BillingClientFactory` if they need it?

**My lean: brief mention in README's limitations.** Cheap to add; sets
expectations for the small subset of apps that need alternative billing.

## How to resolve

Once you've done your manual pass and weighed in on Q1–Q4:

1. **Reply with decisions.** A short `Q1: a, Q2: b, Q3: ack, Q4: brief mention`
   is enough — I'll handle the implementation/docs.
2. **I apply the v0.1.0 decisions** as a small follow-up commit (or roll
   them into the Phase 3 work, depending on what's involved).
3. **Delete this file.** Its content lands in commits/KDoc/CHANGELOG; it
   shouldn't outlive the validation pass.

Until that thumbs-up, **no Phase 3 work begins.** Per the plan, Phase 2.5b
is the only mandatory halt — we wait for your call.
