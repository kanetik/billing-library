# Limitations

Out of scope for the v0.1.x series — see the [Roadmap](../roadmap.md) for what's planned:

- **Subscription-specific helpers, samples, and docs** — protocol-level pass-through works; rich helpers come in v0.2.0.
- **External offers / alternative billing** — apps that need `BillingClient.Builder.enableBillingProgram(...)` provide their own `BillingClientFactory` impl. First-class support is demand-driven.
- **Pre-order full lifecycle helpers** — accessible through `ProductDetails`, but no dedicated helpers in v0.1.x. v0.2.0 docs cover the manual path.
- **`:billing-testing` artifact** — a `FakeBillingRepository` for unit tests + debug-flavor DI overrides is planned for v0.2.0.
