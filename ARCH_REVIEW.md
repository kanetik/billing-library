# Architectural review — `kanetik/billing-library` v0.1.0

> **Status: review complete; one trivial fix applied inline; everything else
> awaits your triage. No code changes from this review until you reply with
> decisions.**

Two reviewers: the on-task subagent (independent fresh-eyes pass on every
file) and my own follow-up pass. Findings are merged; where we disagree or
I extend the agent's point, I note it.

User-set ground rules for this review (from intent-clarifying Q&A):
- **Apply mode**: trivial inline; substantive → notes.
- **Java interop**: Kotlin-first. No `@Jvm*` recommendations unless something is genuinely unusable from Java.
- **ABI stability**: Loose. 0.x is allowed to break between minors.
- **Restructure scope**: Big — module splits + public surface reshape on the table.

---

## Applied inline this pass

**`BillingClientStorage.kt:43-49`** — comment said "30s stopTimeoutMillis grace
window" but the code uses `60_000` (60s). Fixed the comment to match the
code.

That's the only fix applied. Everything below is awaiting your call.

---

## BLOCKERS — must resolve before any v0.1.0 publish

### B1. Public-but-unreachable factory interfaces (the issue I flagged earlier)

**Files:** `factory/BillingClientFactory.kt`, `factory/BillingConnectionFactory.kt`, `BillingRepositoryCreator.kt`

`BillingClientFactory` and `BillingConnectionFactory` are public interfaces
whose KDoc invites consumers to "implement this if you need to stub the
client for unit tests / toggle non-default builder options." But:

- `CoroutinesBillingConnectionFactory` (the only injectable place) is
  `internal`.
- `BillingClientStorage` is `internal`.
- `BillingRepositoryCreator.create()` accepts neither factory.

So a consumer who implements `BillingClientFactory` has nowhere to plug it
in. The KDoc is promising functionality that the v0.1.0 API doesn't deliver.

**Resolution paths**:
- **(a)** Add overloads to `BillingRepositoryCreator.create()` that accept
  one or both factories. Keep the interfaces public.
- **(b)** Mark both factory interfaces `internal`; remove the "for testing"
  KDoc claims; revisit when `:billing-testing` lands in v0.2.0.

**My lean**: **(a)**. It's a small change, makes the public KDoc honest, and
gives consumers a real test seam now. Concretely:

```kotlin
public object BillingRepositoryCreator {
    public fun create(
        context: Context,
        logger: BillingLogger = BillingLogger.Noop,
        billingClientFactory: BillingClientFactory = DefaultBillingClientFactory(),
        connectionFactory: BillingConnectionFactory? = null,  // null = default impl built from billingClientFactory
        scope: CoroutineScope? = null  // null = ProcessLifecycleOwner.get().lifecycleScope
    ): BillingRepository = ...
}
```

Also makes `DefaultBillingClientFactory` public (as the named default).

### B2. `BillingConnectionResult.Success` exposes the raw `BillingClient`

**File:** `BillingConnectionResult.kt:12`

`Success(val client: BillingClient)` is a public data class that hands
consumers a raw `BillingClient`. Two problems:

- It's a footgun: any consumer who pattern-matches on `Success` and uses
  `.client` directly bypasses the entire library — no watchdog, no retry,
  no activity guard. The library's own `connectToBilling()` KDoc actively
  encourages this misuse by describing the flow as "hot and shared."
- ABI trap: `BillingClient`'s shape is not stable across PBL minor versions.
  Pinning a raw `BillingClient` into our public types means PBL's ABI
  becomes our ABI.

The client is only read internally by `EasyBillingRepository.connectToClientAndCall`,
which is itself internal. The public type doesn't need to carry it.

**Resolution**: Drop `client` from the public `Success`. Internal code can
carry the client through a separate internal type
(`internal data class InternalBillingConnectionSuccess(val client: BillingClient)`)
or by keeping a private mutable reference inside `EasyBillingRepository`.

### B3. `connectToClientAndCall` can hang forever after upstream completes

**File:** `EasyBillingRepository.kt:233-240`

`connectToClientAndCall` calls `connectionFlowable.first()`. The upstream
is a `callbackFlow` that can `close(BillingException)` if billing setup
fails; `.catch` then emits a `BillingConnectionResult.Error`. With
`SharingStarted.WhileSubscribed`, when the upstream completes it doesn't
restart on subsequent subscribers — the SharedFlow keeps replaying its
last cached value (the Error), so `first()` returns the error and we
correctly throw.

The hang case: if the upstream is cancelled by scope cancellation (not
caught by `.catch`), the SharedFlow's replay cache may be empty when the
next `first()` call lands. That call suspends forever — no error, no
timeout.

**Subagent flagged this; I agree.** Specifically the
`ProcessLifecycleOwner.lifecycleScope` cancellation path on rare device
edge cases could hit this.

**Resolution**: wrap `first()` with `withTimeout(...)` and throw a typed
exception on timeout. 30 seconds is generous.

### B4. `executeBillingOperation` runs on `Dispatchers.Main` by default

**File:** `EasyBillingRepository.kt:170-181`

`executeBillingOperation`'s `dispatcher` parameter defaults to
`mainDispatcher` (= `Dispatchers.Main`). This means `queryPurchases`,
`queryProductDetails`, `consumePurchase`, `acknowledgePurchase` all run
their retry loops — including `delay()` calls and nested
`queryPurchases` calls inside `handleRetryPrerequisite` — on the main
thread.

`delay` is non-blocking, so it's not literal main-thread spinning, but the
synchronous portions of the retry loop (response inspection, exception
construction, `BillingLoggingUtils` string building) all execute on Main.
Worse, the recursive `queryPurchases` inside the requery-retry path
launches `async`-on-Main from a `withContext(Main)` — pinning everything
to one dispatcher slot.

`launchFlow` legitimately needs Main (PBL requires it). Nothing else does.

**Resolution**: Split into two parameters:

```kotlin
internal class EasyBillingRepository(
    private val billingClientStorage: BillingClientStorage,
    private val logger: BillingLogger = BillingLogger.Noop,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val uiDispatcher: CoroutineDispatcher = Dispatchers.Main,
)
```

`launchFlow` uses `uiDispatcher`; everything else uses `ioDispatcher`.

### B5. `observePurchaseUpdates` re-subscribes inside `flatMapConcat` — drop windows

**File:** `EasyBillingRepository.kt:51-55`

```kotlin
override fun observePurchaseUpdates(): Flow<PurchasesUpdate> {
    return connectionFlowable.flatMapConcat {
        billingClientStorage.purchasesUpdateFlow
    }
}
```

Every emission of `connectionFlowable` cancels the previous inner
collection of `purchasesUpdateFlow` and starts a new one. There's a
narrow window between cancel and re-subscribe where a `PurchasesUpdate`
emitted by PBL is missed. PBL reconnects on auto-reconnection emit a new
`Success` while a purchase is completing — this is exactly the case where
a purchase update could be dropped.

The `purchasesUpdateFlow` is hot (it's a `MutableSharedFlow` written to
by `FlowPurchasesUpdatedListener`). It doesn't depend on connection
state — PBL fires the listener regardless. The flatMapConcat wrap
provides no benefit and creates the drop window.

**Resolution**: return `purchasesUpdateFlow` directly. Drops the
`@ExperimentalCoroutinesApi` annotation as a bonus.

### B6. `BillingConnectionLifecycleManager` leaks its `SupervisorJob`

**File:** `lifecycle/BillingConnectionLifecycleManager.kt:22, 41-43`

`onStop` calls `coroutineContext.cancelChildren()`, which cancels the
collector coroutine but leaves the `SupervisorJob` itself alive. On
activity destroy (no `onDestroy` override here), the `SupervisorJob`
plus the `CoroutineExceptionHandler` lambda (which captures `this`)
remain in memory until the host activity is GC'd. On rotation it's
benign (onStop+onStart cycle is correct); on activity finish it's a
slow leak.

**Resolution**: override `onDestroy` and call `job.cancel()`.

---

## STRONG — should resolve before v0.1.0

### S1. `PurchaseFlowCoordinator` watchdog uses non-atomic `get()` then `set(false)`

**File:** `ext/PurchaseFlowCoordinator.kt:149-157`

The watchdog reads `isPurchaseFlowInProgress.get()`, then calls
`.set(false)`. Two-step. If a second purchase flow launches between the
get and the set (unlikely given the 2-min window, but possible), the
watchdog clears the flag for the new in-flight flow.

**Resolution**: use `compareAndSet(true, false)` — atomic.

### S2. `ProcessLifecycleOwner.get()` in default arg breaks testability

**File:** `BillingClientStorage.kt:17`

The default argument `connectionShareScope = ProcessLifecycleOwner.get().lifecycleScope`
evaluates at construction time. It crashes if `ProcessLifecycleOwner` isn't
initialized yet (Robolectric, unit tests). Combined with B1 — `BillingRepositoryCreator.create()`
exposing no scope parameter — the entire repository is currently unmockable
in unit tests.

**Resolution**: surface a `scope: CoroutineScope?` parameter on
`BillingRepositoryCreator.create()` that gets forwarded down. (Folds
naturally into B1's resolution.)

### S3. `JvmTarget = 21` is too aggressive for Maven Central

**File:** `billing/build.gradle.kts:5-7, 27-31`

`jvmToolchain(21)` and `JavaVersion.VERSION_21` mean class files use Java
21 bytecode. Any consumer app on JVM 17 (or Gradle 8 + AGP 8) gets a class
file version error. JVM 17 is the current consensus floor for Android
libraries; nothing in our source uses Java 21 features.

**Resolution**: drop to JVM 17 (or 11 for max compat). `jvmToolchain(17)`,
`JavaVersion.VERSION_17`.

### S4. `ResultStatus.PENDING` is dead code

**File:** `enums.kt:5`

`PENDING` is declared but `getResultStatus` never emits it (it maps
unrecognized codes to `ERROR`). PBL has a `Purchase.PurchaseState.PENDING`
concept, but this library doesn't surface pending purchases at all. Two
options:

- **(a)** Implement pending-state surfacing: `getResultStatus` checks for
  PENDING-equivalent conditions; `PurchasesUpdate.Pending` sealed variant
  added.
- **(b)** Remove `ResultStatus.PENDING` from the enum (loose ABI allows
  this in 0.x).

**My lean**: **(b)**. Pending purchases are a real PBL concept worth
handling, but the right surface is on `PurchasesUpdate` (a new sealed
variant), not `ResultStatus`. Doing it right is v0.2.0 work; ship without
the dead value.

### S5. `SHA1withRSA` deprecation is silent

**File:** `ext/PurchaseVerifier.kt:124`

Google Play currently signs purchase data with SHA-1 + RSA, so this is
correct today. NIST and Android security docs deprecate SHA-1 for new
signatures, and Google has signaled future migration. If they ever
switch, the constant is a silent failure point — verification rejects
every valid purchase, no compile warning.

**Resolution**: add a `signatureAlgorithm: String` constructor parameter
defaulting to `SHA1withRSA`, so consumers can forward-migrate without
waiting for a library release. Plus a comment on the constant pointing to
Play Console's "Licensing" docs.

### S6. `Kotlin` version isn't pinned in `libs.versions.toml`

**File:** `gradle/libs.versions.toml`

The `kotlin` version is implicit — comes from AGP 9.2.0's bundled Kotlin.
That's a feature of AGP 9, but for a library it means:

- Consumers don't know the exact Kotlin version we compile against.
- If AGP ships a new bundled Kotlin, our library's compiled metadata
  changes silently.

**Resolution**: declare `kotlin = "..."` in `[versions]`, add
`kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }`
in `[plugins]`, apply explicitly in `billing/build.gradle.kts`. Pins
language version + makes it visible.

---

## NICE-TO-HAVE — could ship without

### N1. `PurchaseFlowResult.Success`/`AlreadyInProgress`/etc. should be `data object`

**File:** `ext/PurchaseFlowCoordinator.kt:172-175`

Kotlin 1.9+ supports `data object`, which gives `toString() == "Success"`
instead of `"PurchaseFlowResult$Success@hashcode"`. Better log output for
consumers.

### N2. `PurchaseVerifier` lives in `ext` — wrong package

**File:** `ext/PurchaseVerifier.kt`

`ext` reads as "opt-in extension functions / convenience helpers." But
`PurchaseVerifier` is critical security code that any consumer with real
purchases needs. Burying it in ext alongside `PurchaseFlowCoordinator`
under-sells it.

**Resolution**: Move to either `com.kanetik.billing` root or a dedicated
`com.kanetik.billing.security` (or `.verification`) subpackage. Source-
incompatible after publish, so decide before v0.1.0.

**My lean**: `com.kanetik.billing.security.PurchaseVerifier`.

### N3. `BillingException` subclasses aren't `data class`

**File:** `exception/BillingException.kt:65-180`

Two `NetworkErrorException`s wrapping the same `BillingResult` aren't
`==`. `toString()` doesn't include the result payload. Making each
subclass a `data class` fixes both. Allowed by Kotlin even when
inheriting from `Exception`.

### N4. `consumer-rules.pro` is empty — `BillingLogger` impls might be stripped

**File:** `billing/consumer-rules.pro`

`NoopBillingLogger` and `AndroidLogLogger` are `internal object`s
referenced as values from `BillingLogger.Noop` and `BillingLogger.Android`.
R8's reachability analysis should follow the references through, but
aggressive consumer R8 configs sometimes don't.

**Resolution**: add a defensive keep:
```
-keep class com.kanetik.billing.logging.** { *; }
```
Or more targeted: `-keep class com.kanetik.billing.logging.NoopBillingLogger { *; }` etc.

### N5. `consumePurchase` returns `String?` — null case is undocumented

**File:** `BillingActions.kt:66`, `EasyBillingRepository.kt:117`

KDoc says "or `null` if the underlying call returned no token", but PBL
always returns the token on `OK`. The `?` looks like defensive coding
and forces every caller to null-check.

**Resolution**: drop the `?`. Throw if the token is somehow absent on
success.

### N6. `enums.kt` has multiple top-level types

**File:** `enums.kt`

Kotlin convention is one public type per file (file named after the
type). `enums.kt` houses both `ResultStatus` and `RetryType`.

**Resolution**: split into `ResultStatus.kt` and `RetryType.kt`. Trivial.

### N7. `EasyBillingRepository` keeps the "Easy" prefix from upstream

**File:** `EasyBillingRepository.kt`

Inconsistent with `BillingRepositoryCreator` (no Easy prefix). It's
internal so renaming is low-risk.

**My lean**: rename to `DefaultBillingRepository` or `BillingRepositoryImpl`.

### N8. CHANGELOG missing dated `[0.1.0]` entry

**File:** `CHANGELOG.md`

Currently everything is under `[Unreleased]`. Keep-a-Changelog wants a
dated, versioned section at publish time.

**Resolution**: at v0.1.0 publish (Phase 3 step), rename `[Unreleased]` →
`[0.1.0] - YYYY-MM-DD`. Track this for Phase 3.

---

## JUDGMENT CALLS — for your consideration

### J1. `BillingConnector.connectToBilling()` returns `Flow` but is actually `SharedFlow`

**File:** `BillingConnector.kt:21`

The interface declares `Flow<BillingConnectionResult>`. The impl returns
a `SharedFlow` (via `shareIn`). Returning the `SharedFlow` type
communicates "always hot + shared" through the type system; lets callers
peek `.replayCache`. Tradeoff: more specific type complicates fakes.

### J2. `validatePurchaseActivity` lifecycle gate (`STARTED` vs `RESUMED`)

**File:** `ext/ActivityValidator.kt:33`

`>= STARTED` is defensible; `>= RESUMED` is more conservative.
`launchBillingFlow` internally starts `ProxyBillingActivity`, which needs
the host's window visible — `STARTED` qualifies, but a paused-behind-
transparent-activity edge case is a foot-gun. If you ever see device-
specific billing-flow failures during onStart-to-onResume transitions,
tighten to `RESUMED`. **My lean**: keep `STARTED`; revisit on signal.

### J3. `EXPONENTIAL_RETRY_MAX_TRIES = 4` applies to `launchFlow` too

**File:** `EasyBillingRepository.kt:124-138`, `:340`

`launchFlow` goes through `executeBillingOperation`, which retries up to
4 times on retryable errors. For a UI-initiated action ("user tapped
Buy"), silently retrying behind the scenes for several seconds means the
billing sheet could pop up unexpectedly later if the user has navigated
away.

**Resolution path**: `launchFlow` could short-circuit the retry loop —
maybe single attempt, or only retry on `SERVICE_DISCONNECTED`.

**My lean**: limit `launchFlow` to a single attempt + the
`SERVICE_DISCONNECTED` SIMPLE_RETRY, no exponential. The UI flow benefits
from quick feedback.

### J4. `BillingLoggingUtils.createDetailedBillingContext` runs in superclass constructor

**File:** `exception/BillingException.kt:25-33`

Every `BillingException` instantiation builds the context string eagerly
— `buildList`, string interpolation, sub-response lookup — even if the
exception will never be thrown. `Exception.message` could be made lazy
by overriding `getLocalizedMessage()` instead.

**My lean**: leave it. Eager construction is < 1µs; lazy adds
complexity. Unless someone shows it as a hot path, no.

### J5. `BillingException.fromResult` is public on the companion

**File:** `exception/BillingException.kt:38`

KDoc says "consumers shouldn't typically need to call it" — but it's
public. Either it's a power-user API (document that explicitly) or it
should be `internal`.

**My lean**: keep public, sharpen the KDoc as "advanced — used internally;
exposed for fakes / custom retry logic."

### J6. `BillingActions.queryProductDetailsWithUnfetched` exposes PBL's `QueryProductDetailsResult`

**File:** `BillingActions.kt:50`

Same concern category as B2 (raw PBL types in our public API), but
arguably justifiable — the whole point of this method is to surface
PBL's unfetched-product list, which lives only on the PBL type.
Wrapping it in our own type would be busywork.

**My lean**: leave it. Document that this method is for power users
who need PBL-level diagnostics.

---

## STRUCTURE — module layout opinion

You opted in to "big" restructure scope. My take on the structural questions:

### Single artifact for v0.1.0 — keep it

The plan was to ship one artifact (`com.kanetik.billing:billing`) with
package-level separation between core and ext. After re-reading
everything, I still think that's right.

Arguments for splitting `:billing` into `:billing-core` + `:billing-ext`
now:
- Cleaner ABI separation; consumers who want only plumbing don't pay for ext.

Arguments against:
- Doubles publishing surface on first release.
- Adoption friction ("which artifact do I add?").
- ext is ~400 LOC of clean Kotlin; R8 strips unused stuff anyway.
- v0.2.0 brings `:billing-testing` — we'll already be juggling two
  artifacts then. Three is worse than two.

**Don't split now.** Single artifact + package separation is right.

### Package layout

Current:
- `com.kanetik.billing` — core types
- `com.kanetik.billing.ext` — opt-in helpers
- `com.kanetik.billing.exception` — exception hierarchy
- `com.kanetik.billing.factory` — factory interfaces
- `com.kanetik.billing.lifecycle` — lifecycle helper
- `com.kanetik.billing.logging` — logger interface

If we apply N2 (move `PurchaseVerifier` to `.security`), the layout becomes:
- `.billing.security` — `PurchaseVerifier`

That's clean. **Recommended layout for v0.1.0**:

| Package | Contents |
|---|---|
| `com.kanetik.billing` | `BillingRepository`, `BillingActions`, `BillingConnector`, `BillingPurchaseUpdatesOwner`, `BillingConnectionResult`, `PurchasesUpdate`, `BillingRepositoryCreator`, `ResultStatus`, `RetryType` |
| `com.kanetik.billing.exception` | `BillingException` and subtypes |
| `com.kanetik.billing.factory` | `BillingClientFactory`, `BillingConnectionFactory`, `DefaultBillingClientFactory` (after B1 makes them public-real) |
| `com.kanetik.billing.lifecycle` | `BillingConnectionLifecycleManager` |
| `com.kanetik.billing.logging` | `BillingLogger` |
| `com.kanetik.billing.security` | **`PurchaseVerifier`** (moved from ext per N2) |
| `com.kanetik.billing.ext` | `validatePurchaseActivity`, `toOneTimeFlowParams`, `PurchaseFlowCoordinator`, `PurchaseFlowResult` |

---

## RECOMMENDED TRIAGE

Suggested order:

1. **Decide on B1** (factory injection) — affects the public API of
   `BillingRepositoryCreator.create()` and folds in B4's split-dispatcher
   parameter and S2's scope parameter. One commit covers all three.
2. **B2, B3, B5, B6** — independent. Each is a small focused commit.
3. **Apply S1, S3, S4, S5, S6** — small focused commits each.
4. **N1–N8** — nice-to-haves. Apply the ones you want. N6 (split
   `enums.kt`) and N7 (rename `EasyBillingRepository`) are pure cleanup.
5. **J1–J6** — judgment calls. Decide as you read; some apply now, some
   become CHANGELOG entries.

---

## How to resolve

Reply with what you want applied. A short `B1: a, B2: drop, B3: yes,
B4: yes, B5: yes, B6: yes, S1-S6: yes, N1: yes, N2: security, N3-N8:
yes/no per item, J1: SharedFlow, J3: limit launchFlow retries` works.

I'll group the changes into focused commits — no surprise diffs — and
push for your final review before we move into Phase 3.

This file gets deleted after the triage round resolves.
