# `:sample` — Kanetik Billing Sample app

A minimal single-Activity Compose app that exercises the library end-to-end against Google's `android.test.purchased` static SKU. No Play Console setup required — the static SKU works on any debug build.

## What it demonstrates

- `BillingRepositoryCreator.create(context, BillingLogger.Android)` — entry point with logcat opt-in.
- `BillingConnectionLifecycleManager` registered on the activity — connection stays warm while STARTED, tears down on DESTROYED.
- `connectToBilling()` collected to surface connection status in the UI.
- `queryProductDetails(...)` for `android.test.purchased`.
- `ProductDetails.toOneTimeFlowParams()` — the ext helper that replaces manual `BillingFlowParams.Builder` construction.
- `launchFlow(activity, params)` to open the Play purchase UI.
- `observePurchaseUpdates()` collected in the ViewModel; results feed back into the screen.
- `handlePurchase(purchase, consume = true)` — the convenience method that dispatches to consume vs. acknowledge automatically. The sample uses `consume = true` so the test SKU resets each run; real apps with non-consumables pass `false`.

## Run it

```bash
./gradlew :sample:installDebug
```

Open the app, tap **Query products** to load the test SKU, then **Buy**. Play's test-purchase dialog appears, you confirm, and the log card at the bottom of the screen shows the lifecycle: `purchase update: Success`, `handlePurchase OK`. Each fresh launch with `consume = true` lets you re-purchase.

## Swapping in a real SKU

For testing against a real product:

1. Replace `TEST_PRODUCT_ID` in `SampleViewModel.kt` with your real product ID.
2. Upload the sample to Play Console's **Internal testing** track.
3. Add a license-tester account (Play Console → **Setup → License testing**) and join the internal track from the tester device.
4. Run the test purchase — it auto-refunds within 48 hours.

For non-consumables, change `handlePurchase(purchase, consume = true)` to `consume = false`. The sample will then surface "Item Already Owned" on subsequent purchase attempts (clear app data to reset).

## Why a project reference, not the published artifact?

`sample/build.gradle.kts` uses `implementation(project(":billing"))` rather than `implementation("com.kanetik.billing:billing:0.1.0")`. Library changes propagate to the sample on the next rebuild — no `publishToMavenLocal` step required during library development. Consumers in their own projects use the Maven coordinate; the sample is a development tool, not a consumer.
