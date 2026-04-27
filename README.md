# Kanetik Billing Library

A coroutine-first wrapper around Google Play Billing Library 8.x.

> Pre-release. v0.1.0 publish to Maven Central is in progress; full README,
> installation snippet, and quick-start docs land with that release.

## Status

Bootstrapped from a substantial rewrite of [`michal-luszczuk/MakeBillingEasy`](https://github.com/michal-luszczuk/MakeBillingEasy).
See [`docs/ROADMAP.md`](docs/ROADMAP.md) once it lands for what's coming.

## Limitations

The following are intentionally out of scope for v0.1.0; see `docs/ROADMAP.md`
(coming in v0.1.0 docs work) for what's planned next.

- **External offers / alternative billing.** Apps that need to call
  `BillingClient.Builder.enableBillingProgram(...)` for non-Play payment options
  must currently provide their own `BillingClientFactory` impl. First-class
  support may come in a later release.
- **Subscriptions.** Core APIs (`queryPurchases`, `queryProductDetails`,
  `launchFlow`) pass through to PBL for subscriptions at the protocol level,
  but no subscription-specific helpers, samples, or docs ship in v0.1.0.
  Planned for v0.2.0.
- **Pre-orders.** PBL 8.1+ pre-order details
  (`OneTimePurchaseOfferDetails.preorderDetails`) are reachable through the
  underlying `ProductDetails`, but there's no dedicated helper. Planned
  alongside future multi-offer one-time-product work.
- **Testing artifact.** `:billing-testing` (with a `FakeBillingRepository`
  for unit tests + debug-flavor DI overrides) is planned for v0.2.0.

## License

Apache-2.0 — see [LICENSE](LICENSE).

This project is a substantial rewrite of `michal-luszczuk/MakeBillingEasy`. See
[NOTICE](NOTICE) for full attribution.
