# Testing your billing integration

The Kanetik Billing Library is a thin wrapper over Play Billing — your tests are mostly about exercising the *flow*, not the library. This guide covers the realistic options for testing a billing integration, from cheap automated checks to the full real-Play-dialog round trip.

The README has a short "Testing" section pointing here. This doc goes deeper.

---

## Levels of test realism

Three levels, each appropriate for different work. Pick the cheapest one that exercises what you're trying to verify.

| Level | What you exercise | Setup cost | Shows real dialog? |
|---|---|---|---|
| 1. Static SKU | Wiring: connection, query, observe, handlePurchase | None | No — auto-completes |
| 2. License tester + real product | Full Play flow: dialog, payment, acknowledge | Play Console upload + license tester + product | Yes |
| 3. Play Billing Lab | Edge cases (errors, regions, sub states) on top of Level 2 | Lab app + existing Level 2 setup | Yes (real product) |

---

## Level 1 — Static test SKUs

Google ships four built-in SKUs that any debug build can query without Play Console setup:

| Product ID | Result |
|---|---|
| `android.test.purchased` | Always succeeds with a fabricated `Purchase` |
| `android.test.canceled` | Always returns `USER_CANCELED` |
| `android.test.item_unavailable` | Always returns `ITEM_UNAVAILABLE` |
| `android.test.refunded` | Returns `OK`, but the purchase is in a refunded state |

```kotlin
val params = QueryProductDetailsParams.newBuilder()
    .setProductList(listOf(
        QueryProductDetailsParams.Product.newBuilder()
            .setProductId("android.test.purchased")
            .setProductType(BillingClient.ProductType.INAPP)
            .build()
    ))
    .build()
val products = billing.queryProductDetails(params)
billing.launchFlow(activity, products.first().toOneTimeFlowParams())
```

### What Level 1 verifies

- `connectToBilling()` reaches `Success`
- `queryProductDetails` returns at least one `ProductDetails`
- `launchFlow` accepts the params without throwing
- `observePurchaseUpdates()` collector receives the expected `PurchasesUpdate` variant
- `handlePurchase(purchase, consume = …)` runs through to completion

### What Level 1 does *not* verify

- The Play purchase dialog UI (it's bypassed)
- Real payment-method handling
- Signature verification (the static SKU's `Purchase` carries a fabricated signature)
- Region- or country-specific behavior
- Subscription state transitions
- Network failure paths

### When to use it

- Automated integration tests in CI
- "Does the wiring work after I refactored?" smoke tests
- Demo apps and samples (the `:sample` module in this repo uses `android.test.purchased`)

If your test needs the real dialog or any of the unverified paths above, escalate to Level 2.

---

## Level 2 — License tester + real product

This is the canonical Play testing path. Real dialog, real flow, no actual money.

### One-time setup

1. **Upload your app to Play Console.** Any track works; Internal testing is the lowest-friction option and doesn't require promotion to public users. The signed APK / app bundle just needs to be uploaded once so Play knows about your app and its products.

2. **Create a managed in-app product.** In Play Console → your app → **Monetize → Products → In-app products → Create product**:
   - **Product ID**: any string, e.g. `dev_test_lifetime`. Match this to what your code queries.
   - **Type**: One-time (or Subscription if you're testing subs).
   - **Price**: any — you won't be charged as a tester.
   - **Status**: Active.
   - **Title / description**: anything; this is what shows in the dialog.

3. **Add yourself as a license tester.** Play Console → **Setup → License testing** → add the Google account that's signed in on your test device. License testers get the real dialog and a "test" payment method; charges auto-refund within 48 hours.

### Per-test loop

1. Build the debug APK (`./gradlew :app:assembleDebug`). The test build does not need to be uploaded each time — Play Store recognizes your installed app from its package and signature against what's been uploaded once.
2. Install on a device signed in with the license-tester account.
3. Run the app, query your real product ID, tap Buy.
4. The real Play purchase dialog appears with your product's title, description, price, and "test card" indicator.
5. Confirm — `BillingResult` is `OK`, a real `Purchase` arrives via `observePurchaseUpdates()`, and `Purchase.signature` is a real signature you can verify with `PurchaseVerifier`.
6. Refund: license-tester purchases auto-refund within 48 hours, but you can also clear the purchase manually via Play Store → Account → Order history.

### What Level 2 verifies

Everything Level 1 does, plus:
- Real dialog rendering (catches Theme / Activity-context bugs)
- Signature verification (real `Purchase.signature` validates against your app's public key)
- Acknowledge / consume against the real Play backend
- Region-specific pricing (your tester account's region drives this)

### What Level 2 still doesn't easily verify

- Specific failure scenarios (network down, billing unavailable, item already owned, etc.) — for that, use Level 3.
- Subscription edge states (grace period, account hold, price changes) — Level 3.

---

## Level 3 — Play Billing Lab

[Play Billing Lab](https://developer.android.com/google/play/billing/test#play-billing-lab) is Google's testing companion app, separate from your app and from the Play Store. It runs alongside Level 2 setup and lets you inject failure scenarios and configuration overrides into the same flow.

**What Lab is not**: it does not fabricate products out of thin air. Products still come from your Play Console configuration. Lab augments Level 2; it doesn't replace it.

### Install + activate

1. Install [Play Billing Lab](https://play.google.com/store/apps/details?id=com.google.android.apps.play.billingtestcompanion) on the same device as your test app.
2. Open it. The Dashboard has three sections: **Configuration settings**, **Subscription settings**, **Response simulator**. Each gates a class of test scenarios.
3. Make sure the device is also signed in with your license-tester account (same as Level 2).

### Response simulator — force a specific `BillingResult` code

The most useful Lab feature for library validation. Force any response code on any flow without engineering real failures.

1. Dashboard → **Response simulator → Manage**.
2. Add response codes you want simulated:
   - `USER_CANCELED` → exercises your `PurchasesUpdate.Canceled` branch.
   - `BILLING_UNAVAILABLE` → triggers `BillingException.BillingUnavailableException`.
   - `ITEM_ALREADY_OWNED` → triggers `BillingException.ItemAlreadyOwnedException` and your `PurchasesUpdate.ItemAlreadyOwned` branch.
   - `NETWORK_ERROR` → triggers `BillingException.NetworkErrorException` and the library's exponential-backoff retry loop (since `RetryType.SAFE`).
   - `SERVICE_DISCONNECTED` / `SERVICE_UNAVAILABLE` / `SERVICE_TIMEOUT` — `RetryType.SAFE` paths.
   - `DEVELOPER_ERROR` / `FEATURE_NOT_SUPPORTED` — `RetryType.NEVER` paths.
3. Activate the simulator (toggle in the dashboard).
4. Run your app and exercise the relevant flow — Lab returns the simulated code instead of the real Play response.

This is by far the easiest way to verify every branch of your `BillingException` handling without orchestrating real failures.

### Configuration settings — region overrides, free trials, intro offers

Useful when:
- You need to test how your app behaves for users in a specific country (different currency, regional product availability)
- You're using free trials or introductory offers and want to exercise the offer-selection logic
- You want to test the same code against multiple regions in a single session

Dashboard → **Configuration settings → Add** and configure the override.

### Subscription settings — grace period, account hold, price changes

Sub-specific test states that are hard to engineer organically:
- **Grace period** — payment failed but user still has access
- **Account hold** — payment failed for longer; user lost access
- **Price changes** — user accepting/rejecting a price increase

Dashboard → **Subscription settings → Manage**, select the subscription you've already configured in Play Console, set the state.

(Note: v0.1.0 of this library ships subscription support at the protocol level only; full subs helpers come in v0.2.0 — see [`ROADMAP.md`](ROADMAP.md). You can still test subs flows at this level today using the raw `BillingFlowParams` builder.)

---

## Patterns for testing your *consumer* code

Beyond the levels above, you'll want to test your own ViewModels, repositories, and screens that depend on `BillingRepository`. Some patterns:

### Debug-flavor entitlement override

Many apps short-circuit the real billing repo entirely in debug builds and read entitlement from a `BuildConfig` flag or a debug-menu setting:

```kotlin
// debug/.../PremiumManager.kt
class PremiumManager @Inject constructor(...) {
    val isPremium: StateFlow<Boolean> = MutableStateFlow(BuildConfig.DEBUG_PREMIUM_OVERRIDE)
    // ...
}
```

This lets you develop premium UI without touching billing at all. Combine with Level 1 or Level 2 for tests that actually exercise the billing flow.

### Mocking `BillingRepository` in your unit tests

Until v0.2.0 ships the official `:billing-testing` artifact (with a built-in `FakeBillingRepository`), the cleanest approach in your own test code is mockk against the `BillingRepository` interface:

```kotlin
val billing = mockk<BillingRepository>()
coEvery { billing.queryProductDetails(any()) } returns listOf(productDetails())
coEvery { billing.launchFlow(any(), any()) } returns Unit
every { billing.observePurchaseUpdates() } returns flowOf(PurchasesUpdate.Success(listOf(purchase())))
```

`BillingRepository` is composed of three narrower interfaces (`BillingActions`, `BillingConnector`, `BillingPurchaseUpdatesOwner`) — depend on the narrowest one your code under test needs, and mock that.

The library's own test suite (`billing/src/test/kotlin/com/kanetik/billing/`) uses this pattern with mockk + Truth + kotlinx-coroutines-test. Worth skimming for examples.

### Looking ahead: `:billing-testing`

v0.2.0 plans a published `com.kanetik.billing:billing-testing` artifact with:
- `FakeBillingRepository` — in-memory billing repo with scriptable behavior
- Test-control API: `setConnectionResult`, `emitPurchaseUpdate`, `setProducts`, `throwOnNext(BillingException)`, `simulateLaunchFlowResult`
- Robolectric included so the four classes deferred from v0.1.0's test suite (PurchaseVerifier, toOneTimeFlowParams, DefaultBillingRepository orchestration, showInAppMessages) get coverage

See [`ROADMAP.md`](ROADMAP.md) for the full v0.2.0 plan.

---

## Quick reference

| I want to… | Use |
|---|---|
| Verify wiring (connection, query, observe, handle) | Level 1 — static SKU |
| See the real Play purchase dialog | Level 2 — license tester + real product |
| Force a specific BillingResult code | Level 3 — Play Billing Lab Response simulator |
| Test region-specific pricing | Level 3 — Lab Configuration settings |
| Test sub grace period / account hold / price change | Level 3 — Lab Subscription settings |
| Develop premium UI without touching billing | Debug-flavor entitlement override |
| Unit-test code that depends on `BillingRepository` | mockk against the interface (until v0.2.0's `:billing-testing` ships) |
