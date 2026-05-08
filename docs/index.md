# Kanetik Billing Library

A coroutine-first wrapper around [Google Play Billing Library 8.x](https://developer.android.com/google/play/billing). The [README](https://github.com/kanetik/billing-library#readme) covers what the library is and the bare-minimum integration; this site is the deeper reference.

## What you'll find here

### Getting set up

- [Installation](installation.md) — Gradle (Kotlin DSL, Groovy, version catalog) and Maven snippets.
- [Quick start](quick-start.md) — full one-time-IAP integration, mirrored from the README so site readers don't have to bounce.

### Guides — task-oriented walkthroughs

- [Purchase recovery](guides/purchase-recovery.md) — how the library auto-sweeps stranded unacknowledged purchases on every connection, and how the `Recovered` dedupe works under the covers.
- [Error handling](guides/error-handling.md) — `BillingException` subtypes, `userFacingCategory` for UI, the `HandlePurchaseResult` branches.
- [EntitlementCache](guides/entitlement-cache.md) — opt-in entitlement state machine with grace policy. Includes signed/tamper-resistant storage and migration from unsigned snapshots.
- [Signature verification](guides/signature-verification.md) — `PurchaseVerifier` and the verify-then-handle skeleton.
- [Server-driven revocation](guides/server-driven-revocation.md) — how refunds and chargebacks reach the library via `emitExternalRevocation`. RTDN, FCM, and the non-FCM emit triggers (authoritative-empty `queryPurchases`, debug consume flows).
- [Lifecycle integration](guides/lifecycle.md) — `BillingConnectionLifecycleManager` against an Activity, Fragment, or `ProcessLifecycleOwner`.
- [Logging](guides/logging.md) — Noop/Android default + Crashlytics adapter sketch.
- [Dependency injection](guides/dependency-injection.md) — Hilt and Koin module shapes.
- [Multi-quantity purchases](guides/multi-quantity.md) — granting `purchase.quantity` for consumables.
- [Multi-offer products](guides/multi-offer-products.md) — picking an offer for PBL 8 multi-offer one-time products.
- [Product-details caching](guides/product-details-caching.md) — the recommended consumer-side `StateFlow` pattern.
- [In-app messaging](guides/in-app-messaging.md) — surfacing PBL 8's transactional messaging UI.
- [Subscriptions (v0.1.x)](guides/subscriptions.md) — what works at the protocol level today; the gap closes in v0.2.0.

### Reference

- [API overview](reference/api-overview.md) — top-level types and package layout. The full per-class KDoc is in the [Dokka API reference](api/index.html).
- [Replay semantics](reference/replay-semantics.md) — the three-channel structure of `observePurchaseUpdates()`, plus the connection grace window.
- [Extensions](reference/extensions.md) — the `com.kanetik.billing.ext` helpers (`validatePurchaseActivity`, `toOneTimeFlowParams`, `PurchaseFlowCoordinator`).
- [Limitations](reference/limitations.md) — what's out of scope for the v0.1.x series.

### Other

- [Testing](testing.md) — three-levels approach (static SKUs / license tester / Play Billing Lab) plus consumer-code testing patterns.
- [Roadmap](roadmap.md) — v0.2.0 subscription helpers, `:billing-testing` artifact, plus longer-term demand-driven items.
- [Design notes](design-notes.md) — rationale for v0.1.0 design decisions.

## License

Apache-2.0 — see [LICENSE](https://github.com/kanetik/billing-library/blob/main/LICENSE). This project is a substantial rewrite of [`michal-luszczuk/MakeBillingEasy`](https://github.com/michal-luszczuk/MakeBillingEasy); see [NOTICE](https://github.com/kanetik/billing-library/blob/main/NOTICE).
