# API overview

For full per-class / per-method docs, see the [Dokka API reference](https://kanetik.github.io/billing-library/api/).

## Top-level types

| Type | Role |
|---|---|
| `BillingRepositoryCreator.create(...)` | Public entry point. Returns `BillingRepository`. |
| `BillingRepository : BillingActions, BillingConnector, BillingPurchaseUpdatesOwner, BillingChoiceActions` | Composed interface — depend on the narrowest piece you need. Adds `emitExternalRevocation(token, reason)` for transport-agnostic server-driven revocation — see [Server-driven revocation](../guides/server-driven-revocation.md). |
| `BillingActions` | `queryPurchases`, `queryProductDetails`, `consumePurchase`, `acknowledgePurchase`, `handlePurchase`, `launchFlow`, `showInAppMessages`, `isFeatureSupported`. |
| `BillingChoiceActions` *(experimental)* | `isBillingChoiceAvailable`, `getBillingChoiceInfo`, `showBillingProgramInformationDialog`. PBL 9.1.0 Billing Choice surface; every method requires `@OptIn(ExperimentalBillingChoiceApi::class)`. Enrollment- and region-gated — see [Billing Choice](../guides/billing-choice.md). |
| `BillingConnector` | `connectToBilling(): SharedFlow<BillingConnectionResult>`. Transient `startConnection` failures are retried internally per `ConnectionRetryPolicy` before an `Error` is emitted — see [Error handling](../guides/error-handling.md#connection-setup-retry). Also `suspend fun queryBillingAvailability(): BillingAvailability` — a deterministic `AVAILABLE` / `UNAVAILABLE` / `UNKNOWN` verdict that separates a *terminal* "this device can't pay" state from a *transient* failure, for safely gating irreversible decisions; defaulted to `UNKNOWN` on the interface. See [Deterministic availability](../guides/error-handling.md#deterministic-availability-for-irreversible-decisions). |
| `ConnectionRetryPolicy` | Bounded retry (attempts + backoff) for transient connection-setup failures. Pass via `BillingRepositoryCreator.create(connectionRetryPolicy = …)`; `ConnectionRetryPolicy.None` opts out. |
| `BillingPurchaseUpdatesOwner` | `observePurchaseUpdates(): Flow<PurchaseEvent>`. Hot internally; merges three channels — a no-replay live channel, a replay=1 recovery channel, and a replay=16 revocation channel (for `PurchaseRevoked` events pushed via `emitExternalRevocation`; sized for FCM-burst replay) — see [Replay semantics](replay-semantics.md). |
| `BillingException` (sealed) | 13 subtypes — 12 covering PBL response codes (each with a `RetryType` hint) plus `WrappedException` for non-PBL throwables surfaced through `handlePurchase` or while establishing the connection. |
| `BillingClientFactory` | Public test seam — swap `DefaultBillingClientFactory` to alter `BillingClient.Builder`. |
| `BillingLogger` | Pluggable logger (`Noop`, `Android`, or your own adapter). |

## Package layout

Where each public type lives. IDE auto-import handles most of these, but here's the canonical map:

| Subpackage | Contains |
|---|---|
| `com.kanetik.billing` | `BillingRepository`, `BillingRepositoryCreator`, `BillingActions`, `BillingConnector`, `BillingPurchaseUpdatesOwner`, `BillingConnectionResult`, `BillingAvailability`, `ConnectionRetryPolicy`, `PurchaseEvent`, `OwnedPurchases`, `FlowOutcome`, `PurchaseRevoked`, `RevocationReason`, `HandlePurchaseResult`, `BillingInAppMessageResult`, `ProductDetailsQuery`, `RetryType`, `ResultStatus` |
| `com.kanetik.billing.exception` | `BillingException` (sealed) and its 13 subtypes; `BillingErrorCategory` enum |
| `com.kanetik.billing.logging` | `BillingLogger` interface + `Noop` + `Android` |
| `com.kanetik.billing.lifecycle` | `BillingConnectionLifecycleManager` |
| `com.kanetik.billing.factory` | `BillingClientFactory`, `DefaultBillingClientFactory`, `BillingChoiceClientFactory` *(experimental)* |
| `com.kanetik.billing.choice` *(experimental)* | `BillingChoiceActions`, `BillingChoiceAvailability`, `BillingChoiceDetails`, `ChoiceScreenType`, `ExperimentalBillingChoiceApi` |
| `com.kanetik.billing.ext` | `validatePurchaseActivity`, `ProductDetails.toOneTimeFlowParams`, `PurchaseFlowCoordinator`, `PurchaseFlowResult` |
| `com.kanetik.billing.security` | `PurchaseVerifier` |
| `com.kanetik.billing.entitlement` | `EntitlementCache`, `EntitlementState`, `GracePolicy`, `GraceReason`, `EntitlementSnapshot`, `EntitlementStorage` |
| `com.kanetik.billing.entitlement.signed` | `SignedEntitlementStorage`, `KeystoreBackedKeyProvider`, `ServerSeededKeyProvider`, `SharedPreferencesSignatureStore`, `TamperEvent` |
