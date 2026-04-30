# Build history — v0.1.0

The intent and design rationale behind what shipped in v0.1.0. Distinct from `CHANGELOG.md` (which lists user-visible changes per release) and `ROADMAP.md` (which tracks what's next). This doc captures *why the library is shaped the way it is* — useful for contributors landing in the repo months later, or future-Jeremy when reopening this project after a long pause.

## Origins

The library started as a fork of [`michal-luszczuk/MakeBillingEasy`](https://github.com/michal-luszczuk/MakeBillingEasy) (Apache-2.0, last updated December 2022) and has been substantially rewritten:

- Google Play Billing Library 8.x baseline (was 4.x in upstream)
- PBL 8 features wired through: `enableAutoServiceReconnection`, sub-response codes, `enableOneTimeProducts`
- Typed exception hierarchy with retry-type classification
- Lifecycle-aware connection sharing via `WhileSubscribed`
- Per-call exponential backoff
- Hardened `launchFlow` error mapping
- Pluggable logger interface (was hardcoded `Log.d` in upstream)

Upstream's prefix was `com.luszczuk.makebillingeasy`; we renamed to `com.kanetik.billing` for the new artifact. Apache-2.0 obligations are met via `LICENSE` and `NOTICE` (which credits the upstream author and states the library is a substantial rewrite). Consider opening a courtesy issue on the upstream repo pointing here as a maintained continuation.

## Locked-in v0.1.0 decisions

| Decision | Value |
|---|---|
| Library name | Kanetik Billing Library |
| GitHub repo | `github.com/kanetik/billing-library` |
| Maven coordinate | `com.kanetik.billing:billing` |
| License | Apache-2.0 (preserved from upstream) |
| v0.1.0 scope | One-time IAP, single artifact, ext package shipped inside core JAR |
| Logger API | `BillingLogger` interface with `d/w/e`; `Noop` (silent) default + built-in `Android` impl; consumer wires Crashlytics via custom adapter |
| Beta tag format | `vX.Y.Z-betaN` (e.g. `v0.1.0-beta1`) |
| GA tag format | `vX.Y.Z` |
| POM developer | `Kanetik <billinglibrary@kanetik.com>` |
| Minimum SDK | 23 (PBL 8.1 raised the floor — was originally 21 before architectural review caught it) |
| JVM target | 11 (no JDK 17+ language features used; max consumer compatibility) |
| Subscriptions | Protocol-level pass-through only in v0.1.0; full helpers + sample in v0.2.0 |
| Java interop | Kotlin-first; no `@Jvm*` annotations unless something is genuinely unusable from Java |
| ABI stability for 0.x | Loose — 0.x can break between minors per semver |

## Public surface (v0.1.0)

### Entry point

`BillingRepositoryCreator.create(context, logger?, billingClientFactory?, scope?, ioDispatcher?, uiDispatcher?)` returns a `BillingRepository`. `BillingClientFactory` (interface) and `DefaultBillingClientFactory` (default impl) are public — this IS the test seam consumers want for swapping the underlying `BillingClient` builder.

### Repository interface

`BillingRepository : BillingActions, BillingPurchaseUpdatesOwner, BillingConnector`. Concrete impl `DefaultBillingRepository` is internal. The narrower interfaces let consumers depend on only the parts they use (e.g., a UI layer that just observes purchase updates can take `BillingPurchaseUpdatesOwner` instead of the full repository).

### Connection contract

`BillingConnector.connectToBilling()` returns `SharedFlow<BillingConnectionResult>`. `Success` is a `data object` (no `BillingClient` leak — keeps the live PBL client out of public API). The library's internal use of the live `BillingClient` flows through an internal `InternalConnectionState` sealed type — consumers never see it.

### Purchase updates

`BillingPurchaseUpdatesOwner.observePurchaseUpdates()` returns `SharedFlow<PurchasesUpdate>`. Returns the underlying flow directly (no `flatMapConcat`-induced re-subscription window). `PurchasesUpdate` has variants `Success`, `Pending`, `Canceled`, `ItemAlreadyOwned`, `ItemUnavailable`, `UnknownResponse`. `Pending` carries a cardinal-rule KDoc warning against entitlement grants on pending purchases.

### Actions

`BillingActions` exposes `isFeatureSupported`, `queryPurchases`, `queryProductDetails` + `queryProductDetailsWithUnfetched` (returns `ProductDetailsQuery` wrapper), `consumePurchase(params)` + `consumePurchase(purchase)` overload, `acknowledgePurchase(params)` + `acknowledgePurchase(purchase)` overload (with `isAcknowledged` short-circuit), `handlePurchase(purchase, consume: Boolean)` helper, `launchFlow`, `showInAppMessages(activity, params)` returning `BillingInAppMessageResult` sealed type.

### Errors

`BillingException` (sealed) with 12 subtypes, each with a `RetryType` hint. `fromResult(...)` is internal; subtype constructors are public for consumers providing custom factories. `message` is lazy; `toString()` is class-aware for log readability.

### Ext

- `validatePurchaseActivity(activity)` — RESUMED gate (not just STARTED, per architectural review J2)
- `ProductDetails.toOneTimeFlowParams(obfuscatedAccountId?, offerSelector?)` — multi-offer one-time products supported via the lambda
- `PurchaseFlowCoordinator` — in-flight guard with `compareAndSet` watchdog + correlation-id logging, sealed `PurchaseFlowResult` with `data object` variants

### Security

`com.kanetik.billing.security.PurchaseVerifier` — RSA signature verification with pluggable `signatureAlgorithm`, defaults to PBL-current `SHA1withRSA`.

### Lifecycle

`BillingConnectionLifecycleManager` overrides `onStart`/`onStop`/`onDestroy` (the last for full job cancellation on activity finish).

### Logging

`BillingLogger` interface; `Noop` (silent default) + `Android` (logcat opt-in) shipped. Consumer-rules.pro keeps both impls so R8 can't strip them.

### Internal-only

`DefaultBillingRepository`, `BillingClientStorage`, `FlowPurchasesUpdatedListener`, `CoroutinesBillingConnectionFactory`, `BillingConnectionFactory` interface, `InternalConnectionState`, `BillingLoggingUtils`, `NoopBillingLogger`, `AndroidLogLogger`.

## Internal correctness invariants

These behaviors should be preserved through any refactor — they each fix a specific class of bug or design concern:

- `connectToClientAndCall` wraps `connectionFlow.first()` in `withTimeout(30_000)` — guards against scope-cancellation paths that skip the upstream `.catch` handler. Timeout surfaces as `ServiceUnavailableException`.
- `launchFlow` passes `maxAttempts = 1` to `executeBillingOperation` — UI-initiated flows shouldn't silently retry behind the user's back. Single attempt; user can tap Buy again if it failed.
- Dispatcher split: `ioDispatcher` (default `Dispatchers.IO`) for queries / consume / acknowledge / retry loop; `uiDispatcher` (default `Dispatchers.Main`) only for `launchFlow` and `showInAppMessages`. Consumers can override either independently.
- `BillingClientStorage.connectionFlow` and `connectionResultFlow` both use `WhileSubscribed(60_000)` grace — avoids reconnection churn while letting the connection eventually release. Documented in README so consumers know it's deliberate.
- `PurchaseFlowCoordinator` watchdog uses `compareAndSet(true, false)` — atomic check-and-clear.

## Why the design is shaped this way

### Why a single `:billing` artifact (not split into `:billing-core` + `:billing-ext`)

Single artifact + package separation is enough. Ext is ~400 LOC of clean Kotlin; R8 strips unused. v0.2.0 brings `:billing-testing` which already adds artifact count — three artifacts is worse than two. Revisit only if a consumer's R8 config can't strip ext (very unlikely).

### Why subs aren't in v0.1.0

Designing subs helpers without a real-app driver tends to produce bad ergonomics. Wakey is one-time-IAP only; we'll know what subs ergonomics should look like once Wakey or app-revenue-tracker wires subs into a real app. Pass-through API works in the meantime — consumers can build subs flows with raw `QueryPurchasesParams` / `BillingFlowParams`.

### Why `SharedFlow` (not `Flow`) on the public connection / purchase-update API

SharedFlow communicates the "always hot + shared" contract through the type system; lets consumers peek `replayCache` without observing the flow. The only downside (more specific type complicates fakes) is solved by `:billing-testing`'s upcoming fake — consumers can lean on that artifact rather than rolling their own.

### Why `connectionResultFlow.Success` is a `data object` (not `data class(client: BillingClient)`)

Exposing the live `BillingClient` would leak a non-versioned PBL implementation type into our public ABI, and would tempt consumers into bypassing the library's retry/timeout/error-mapping logic. Internal coordination uses an internal `InternalConnectionState` sealed type that does carry the client; the public flow projects to the safe shape.

### Why exceptions, not `Result`

`BillingException` subtypes carry a `RetryType` hint that consumers branch on to decide retry vs. surface-to-user. A `Result<T, BillingException>` API would be slightly nicer at call sites, but Kotlin's coroutines + `runCatching` already provide that ergonomics on top, and the throw API is more familiar to most Android consumers. We may revisit around 1.0.

### Why a pluggable `BillingLogger` interface

The library has ~17 log sites (timing, errors, billing-result decoding) that production apps want routed to Crashlytics or similar. Hardcoding logcat means consumers have to patch the lib or duplicate every log site at the call boundary. The interface gives `Noop` for silent default, `Android` for one-line logcat opt-in, and `Crashlytics` (or whatever) via a small consumer-side adapter. Adapter examples are in the README.

### Why ext helpers ship inside the core JAR

`validatePurchaseActivity`, `toOneTimeFlowParams`, `PurchaseFlowCoordinator`, `PurchaseVerifier` are "would-have-pasted-them-anyway" patterns lifted from Wakey's `BasePremiumManager`. Splitting them into `:billing-ext` adds artifact count for ~400 LOC. R8 strips unused, so apps that don't use ext don't pay for it.

### Why we do NOT cache `ProductDetails`

Consumers wrap `queryProductDetails` in a `StateFlow` if they want session-level caching. Caching at the library boundary is a tax for consumers who already have their own cache (most production apps), and makes invalidation semantics murky (when does a price change land?). Documented in README as the recommended pattern.

## Decisions explicitly considered and rejected

These were considered during the architectural review and PBL research; documented here so they don't get reopened in future sessions. Each has reasoning that should still hold; revisit only if the underlying assumption changes.

| Decision | Rejection reason |
|---|---|
| `ConnectionTimeoutException` as a separate `BillingException` subtype | The 30s `withTimeout` already throws `ServiceUnavailableException` (via `fromResult` mapping a synthetic `SERVICE_UNAVAILABLE` result). "Connection didn't establish in time" is semantically a service-unavailable condition. A new subtype would make the hierarchy more granular without giving consumers a meaningful new branching opportunity. |
| Splitting `:billing` into `:billing-core` + `:billing-ext` for v0.1.0 | See "single artifact" above. |
| Pinning Kotlin version explicitly in `libs.versions.toml` | AGP 9.2 bundles its own Kotlin and explicitly recommends *not* applying `kotlin-android` separately. Doing so risks version-conflict issues without a clear benefit for a v0.1.0 library. Documented the pin path in `libs.versions.toml` for future override. |
| `@Jvm*` annotations for Java interop | Locked-in decision: Kotlin-first. 2026 Android lib ecosystem is Kotlin-dominant; @Jvm* noise isn't worth the source-clutter cost unless Java consumers actually appear and complain. |
| Server-side / RTDN integration in the library | Out of scope for a client library. RTDN is fundamentally a Cloud Pub/Sub server concern. README documents the boundary and points to Google's RTDN docs. |
| `BillingException` subtypes as `data class` | PBL's `BillingResult` lacks content-based equality, so data-class `equals` would still be identity-based on the result field — no consumer benefit. The toString improvement (which was the real readability win) was achieved via an `override fun toString()` on the sealed superclass instead. |
| `getConnectionState()` direct accessor for v0.1.0 | `SharedFlow<BillingConnectionResult>` + `replay = 1` already lets consumers do `flow.replayCache.firstOrNull()` to peek at the current state. Adding a separate API for the same info is bloat. (Listed in `ROADMAP.md` as a future-if-demanded enhancement.) |
| `getBillingConfigAsync` exposure for v0.1.0 | Niche feature for region-specific UX. Defer until requested. |
| `BillingActions.handlePurchase(purchase, isConsumable: (Purchase) -> Boolean)` predicate-lambda variant | We *did* add `handlePurchase(purchase, consume: Boolean)`. Rejected the *predicate-lambda* form — devs already know the consume/acknowledge decision at the call site; passing a `Boolean` is clearer than passing a function. Predicate would only be useful for batch processing, which the consumer can do with their own loop. |
| Consumer-rules.pro keep for full library package | Kept only the two `BillingLogger` impl objects. Other types are reachable via consumer code (typed exception subtypes, sealed variants) so R8 follows them naturally. PBL and kotlinx-coroutines ship their own consumer-rules already. |
| Returning `Flow` (not `SharedFlow`) from `connectToBilling` / `observePurchaseUpdates` | See "why SharedFlow" above. |

## Test strategy

58 unit tests across 8 files in `billing/src/test/kotlin/com/kanetik/billing/`, using `junit`, `mockk`, `truth`, `kotlinx-coroutines-test`. Pure-JVM with `testOptions.unitTests.isReturnDefaultValues = true` to handle Android-stubbed methods (`TextUtils`, `Looper`, `JSONObject`) without dragging in Robolectric.

| File | Tests | Coverage |
|---|---|---|
| `BillingExceptionTest` | 15 | Every `BillingResponseCode` → typed subtype + `RetryType`; null-result survival; lazy message; class-aware toString |
| `BillingLoggingUtilsTest` | 9 | Detail-context builder paths, null debug-message safety, logBillingFailure routing, logBillingFlowFailure |
| `BillingLoggerTest` | 2 | `Noop` discards; singleton stability |
| `ActivityValidatorTest` | 8 | Every lifecycle state vs RESUMED gate; finishing/destroyed combinations; non-LifecycleOwner activity |
| `HandlePurchaseTest` | 4 | PURCHASED+consume dispatch matrix; PENDING/UNSPECIFIED no-op |
| `FlowPurchasesUpdatedListenerTest` | 9 | Pending/settled partition incl. mixed-state, null purchases callback, every response-code branch |
| `PurchaseFlowCoordinatorTest` | 8 | State machine (Success/InvalidActivityState/AlreadyInProgress/BillingUnavailable/Error/Cancellation/markComplete/Watchdog) |
| `BillingConnectionLifecycleManagerTest` | 3 | onStart/onStop/onDestroy job discipline |

The test suite caught a real production bug — `BillingLoggingUtils.createDetailedBillingContext` was NPEing on null `debugMessage` (which `BillingResult()`'s no-arg constructor produces, used in the connection-factory error fallback). Fixed before any publish.

### Deferred to v0.2.0's `:billing-testing` artifact

These need Robolectric (or instrumented tests) and are better served once that artifact lands:

- `PurchaseVerifier` — uses `android.util.Base64`, requires Robolectric for JVM unit tests.
- `ProductDetails.toOneTimeFlowParams` — PBL's `BillingFlowParams.ProductDetailsParams.build()` does strict internal validation that conflicts with partial mockk-relaxed `ProductDetails`; selector logic is small + covered by `:sample` integration use until the artifact lands.
- `DefaultBillingRepository` orchestration tests — retry loop, `connectToClientAndCall` `withTimeout` behavior, `launchFlow` error wrapping, `queryProductDetailsWithUnfetched` mapping. Mocking the billing-ktx suspend extensions (`client.queryPurchasesAsync(...)` etc.) via `mockkStatic` is brittle against PBL version bumps. The orchestration is small (~30 lines) and every piece it orchestrates is independently tested in v0.1.0 (exception mapping, listener partition, lifecycle, ext helpers). Robolectric in v0.2.0 lets these run against real PBL builders + dispatchers.
- `showInAppMessages` — `InAppMessageResult` is final + has no easily-buildable test fixture. Cover via `:sample` integration use until the artifact arrives.

## Trademark / license guardrails

- "Kanetik Billing" is functional/descriptive — fine. Don't put "Google", "Play", or "Android" in the library name. README "wrapper around Google Play Billing Library" is allowed nominative use.
- Avoid endorsement language ("official", "endorsed by", "Google's billing made easy").
- Apache-2.0 §4 obligations: keep `LICENSE`, attribute upstream in `NOTICE`, state the work is a substantial rewrite of `michal-luszczuk/MakeBillingEasy` in the README. Optional courtesy: open an upstream issue noting the continuation.
