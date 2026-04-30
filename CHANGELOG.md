# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Google Play Billing Library 8.x baseline** — `enableAutoServiceReconnection`,
  sub-response code support, `enableOneTimeProducts`.
- **Coroutine-first public API** —
  `BillingRepositoryCreator.create(context, logger?, billingClientFactory?, scope?, ioDispatcher?, uiDispatcher?)`
  returns a `BillingRepository` composed of `BillingActions` + `BillingConnector`
  + `BillingPurchaseUpdatesOwner`.
- **Typed exception hierarchy** — `BillingException` (sealed) with 12 subtypes,
  each carrying a `RetryType` hint (`SAFE`, `UNSAFE`, `NEVER`) so consumers can
  branch retry-vs-surface decisions without inspecting response codes.
- **Lifecycle-aware connection sharing** — `BillingConnectionLifecycleManager`
  observes `onStart`/`onStop`/`onDestroy`. Connection is shared via
  `SharingStarted.WhileSubscribed(60_000)` to avoid reconnection churn while
  letting the connection eventually release.
- **Exponential backoff** for retryable failures, capped at three attempts.
  `launchFlow` opts out (single attempt) so UI-initiated purchases never
  silently retry behind the user.
- **`PurchasesUpdate` sealed type** — `Success`, `Pending`, `Canceled`,
  `ItemAlreadyOwned`, `ItemUnavailable`, `UnknownResponse(code)`. `Pending`
  carries a cardinal-rule KDoc warning against entitlement grants on
  pending purchases.
- **`handlePurchase(purchase, consume: Boolean)` helper** — bakes in the
  `purchaseState == PURCHASED` no-op guard plus the consume-vs-acknowledge
  dispatch.
- **Convenience overloads** — `consumePurchase(Purchase)` and
  `acknowledgePurchase(Purchase)` (the latter with an `isAcknowledged`
  short-circuit per Google's explicit guidance).
- **`showInAppMessages(activity, params)`** — exposes PBL 8's transactional
  messaging UI ("fix your payment method") with a sealed
  `BillingInAppMessageResult` (`NoActionNeeded` | `SubscriptionStatusUpdated`)
  so PBL's `InAppMessageResult` shape doesn't pin our ABI.
- **Pluggable `BillingLogger` interface** with `BillingLogger.Noop` (silent
  default) and `BillingLogger.Android` (logcat opt-in). Threaded through
  ~17 internal log sites; consumer wires Crashlytics or similar via a small
  custom adapter.
- **Opt-in extensions** in `com.kanetik.billing.ext`:
  - `validatePurchaseActivity(activity)` — RESUMED gate (not just STARTED),
    handles finishing/destroyed and non-LifecycleOwner activities.
  - `ProductDetails.toOneTimeFlowParams(obfuscatedAccountId?, offerSelector?)`
    — multi-offer one-time products supported via the lambda.
  - `PurchaseFlowCoordinator` — in-flight guard with `compareAndSet` watchdog
    + correlation-id logging; sealed `PurchaseFlowResult` with `data object`
    variants (`Success`, `InvalidActivityState`, `AlreadyInProgress`,
    `BillingUnavailable`, `Error(cause)`).
- **Signature verification helper** —
  `com.kanetik.billing.security.PurchaseVerifier` does RSA signature
  verification with a pluggable `signatureAlgorithm` (defaults to
  PBL-current `SHA1withRSA`).
- **Public test seam** — `BillingClientFactory` interface +
  `DefaultBillingClientFactory` impl; consumers can swap the underlying
  `BillingClient` builder without forking the library.
- **58 unit tests** across 8 files covering exception mapping, logger
  routing, activity validation, purchase dispatching, listener partition
  logic, purchase-flow coordinator state machine, and lifecycle manager
  job discipline. Pure-JVM (no Robolectric); `:billing-testing` artifact
  with Robolectric coverage planned for v0.2.0.
- **Maven Central publishing infrastructure** — vanniktech maven-publish
  plugin wiring, Apache-2.0 + scm + developer POM metadata, GitHub Actions
  CI on PR/main and tag-driven publish to Sonatype Central Portal staging.
- **Documentation** — `/docs/MANUAL_SETUP.md`, `/docs/BUILD_HISTORY.md`,
  `/docs/ROADMAP.md`.

### Attribution

This project is a substantial rewrite of
[`michal-luszczuk/MakeBillingEasy`](https://github.com/michal-luszczuk/MakeBillingEasy)
(Apache-2.0, last upstream update December 2022). The core architecture
(connection factory, retry loop, sealed result types) is rewritten on top
of Play Billing Library 8.x with PBL-8-specific features, typed exceptions,
lifecycle awareness, pluggable logging, and ext helpers added.
