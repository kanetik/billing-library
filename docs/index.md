# Kanetik Billing Library

A coroutine-first wrapper around [Google Play Billing Library 8.x](https://developer.android.com/google/play/billing). Typed errors with retry-type hints, lifecycle-aware connection sharing, exponential backoff, and opt-in helpers for the patterns most apps reimplement themselves.

## Why

- The coroutine wrappers — `connectToBilling()`, `queryProductDetails(...)`, `launchFlow(...)`, `observePurchaseUpdates()` — replace PBL's listener/callback wiring at the call site. `observePurchaseUpdates()` returns a `Flow<PurchaseEvent>` split into two sealed roots: `OwnedPurchases` (`Live`, `Recovered`) for owned-state updates and `FlowOutcome` (`Pending`, `Canceled`, etc.) for purchase-flow attempt outcomes. The split is what stops you from accidentally writing a `Canceled` event's `purchases` list into your entitlement cache.
- Every `BillingResponseCode` lands as a typed `BillingException` subtype with a `RetryType` hint. Branch on the type, not on integers.
- `BillingConnectionLifecycleManager` keeps the connection warm while an activity (or process) is observable and tears it down on destruction. There's a 60-second grace window so configuration changes don't churn the connection.

## Where to start

- **New here?** Read [Installation](installation.md) and the [Quick start](quick-start.md). The quick start is a complete one-time-IAP integration with the warning callout that explains the two-tier `PurchaseEvent` design.
- **Already integrated and need to handle a specific concern?** Jump to a guide:
    - [Purchase recovery](guides/purchase-recovery.md) — how the library auto-sweeps stranded unacknowledged purchases on every connection
    - [Error handling](guides/error-handling.md) — `BillingException` subtypes, `userFacingCategory`, and how to handle `handlePurchase` results
    - [EntitlementCache](guides/entitlement-cache.md) — the opt-in entitlement state machine, including signed/tamper-resistant storage
    - [Server-driven revocation](guides/server-driven-revocation.md) — how refunds and chargebacks flow into the library via `emitExternalRevocation`
- **Looking for an API?** [API overview](reference/api-overview.md) for the high-level map; the [Dokka API reference](api/index.html) for the full generated KDoc.
- **Testing your integration?** [Testing guide](testing.md) covers static SKUs, license-tester real-product flows, and Play Billing Lab.
- **What's coming next?** [Roadmap](roadmap.md). The v0.1.x library supports subscriptions at the protocol level; v0.2.0 adds typed subscription helpers, the `:billing-testing` artifact, and more.

## Why this exists

This project is a substantial rewrite of [`michal-luszczuk/MakeBillingEasy`](https://github.com/michal-luszczuk/MakeBillingEasy). The original was a useful starting point but missed the patterns this library hard-codes: typed exceptions, lifecycle-aware connection sharing, the two-tier `PurchaseEvent` split, the auto-recovery sweep, and the opt-in `EntitlementCache`. See [Design notes](design-notes.md) for the rationale behind specific decisions, and [NOTICE](https://github.com/kanetik/billing-library/blob/main/NOTICE) for the full attribution.

## License

Apache-2.0 — see [LICENSE](https://github.com/kanetik/billing-library/blob/main/LICENSE).
