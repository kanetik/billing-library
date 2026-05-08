# API overview

For full per-class / per-method docs, see the [Dokka API reference](https://kanetik.github.io/billing-library/api/).

## Top-level types

| Type | Role |
|---|---|
| `BillingRepositoryCreator.create(...)` | Public entry point. Returns `BillingRepository`. |
| `BillingRepository : BillingActions, BillingConnector, BillingPurchaseUpdatesOwner` | Composed interface — depend on the narrowest piece you need. Adds `emitExternalRevocation(token, reason)` for transport-agnostic server-driven revocation — see [Server-driven revocation](../guides/server-driven-revocation.md). |
| `BillingActions` | `queryPurchases`, `queryProductDetails`, `consumePurchase`, `acknowledgePurchase`, `handlePurchase`, `launchFlow`, `showInAppMessages`, `isFeatureSupported`. |
| `BillingConnector` | `connectToBilling(): SharedFlow<BillingConnectionResult>`. |
| `BillingPurchaseUpdatesOwner` | `observePurchaseUpdates(): Flow<PurchaseEvent>`. Hot internally; merges three channels — a no-replay live channel, a replay=1 recovery channel, and a replay=16 revocation channel (for `PurchaseRevoked` events pushed via `emitExternalRevocation`; sized for FCM-burst replay) — see [Replay semantics](replay-semantics.md). |
| `BillingException` (sealed) | 13 subtypes — 12 covering PBL response codes (each with a `RetryType` hint) plus `WrappedException` for non-PBL throwables surfaced through `handlePurchase`. |
| `BillingClientFactory` | Public test seam — swap `DefaultBillingClientFactory` to alter `BillingClient.Builder`. |
| `BillingLogger` | Pluggable logger (`Noop`, `Android`, or your own adapter). |

## Package layout

Where each public type lives. IDE auto-import handles most of these, but here's the canonical map:

| Subpackage | Contains |
|---|---|
| `com.kanetik.billing` | `BillingRepository`, `BillingRepositoryCreator`, `BillingActions`, `BillingConnector`, `BillingPurchaseUpdatesOwner`, `BillingConnectionResult`, `PurchaseEvent`, `OwnedPurchases`, `FlowOutcome`, `PurchaseRevoked`, `RevocationReason`, `HandlePurchaseResult`, `BillingInAppMessageResult`, `ProductDetailsQuery`, `RetryType`, `ResultStatus` |
| `com.kanetik.billing.exception` | `BillingException` (sealed) and its 13 subtypes; `BillingErrorCategory` enum |
| `com.kanetik.billing.logging` | `BillingLogger` interface + `Noop` + `Android` |
| `com.kanetik.billing.lifecycle` | `BillingConnectionLifecycleManager` |
| `com.kanetik.billing.factory` | `BillingClientFactory`, `DefaultBillingClientFactory` |
| `com.kanetik.billing.ext` | `validatePurchaseActivity`, `ProductDetails.toOneTimeFlowParams`, `PurchaseFlowCoordinator`, `PurchaseFlowResult` |
| `com.kanetik.billing.security` | `PurchaseVerifier` |
| `com.kanetik.billing.entitlement` | `EntitlementCache`, `EntitlementState`, `GracePolicy`, `GraceReason`, `EntitlementSnapshot`, `EntitlementStorage` |
| `com.kanetik.billing.entitlement.signed` | `SignedEntitlementStorage`, `KeystoreBackedKeyProvider`, `ServerSeededKeyProvider`, `SharedPreferencesSignatureStore`, `TamperEvent` |
