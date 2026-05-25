# Pre-order / multi-offer one-time products

Play Billing lets a one-time product carry multiple offers — a pre-order discount alongside the regular price, alternative price tiers, etc. The `toOneTimeFlowParams` extension takes an optional selector:

```kotlin
val params = productDetails.toOneTimeFlowParams(
    offerSelector = { offers ->
        offers.firstOrNull { it.offerId?.contains("preorder") == true }
            ?: offers.firstOrNull()
    }
)
```

Default selector picks the first offer. If your app has only one offer per product, you can ignore the parameter.

## Pre-orders: use `isPreorder`

PBL 8.1+ exposes pre-order metadata via `OneTimePurchaseOfferDetails.preorderDetails`. The library wraps that as a `Boolean` extension for ergonomic switching:

```kotlin
import com.kanetik.billing.ext.isPreorder

val params = productDetails.toOneTimeFlowParams(
    offerSelector = { offers ->
        val preorder = offers.firstOrNull { it.isPreorder }
        val regular = offers.firstOrNull { !it.isPreorder }
        if (userOptedIntoPreorder) preorder ?: regular else regular ?: preorder
    }
)
```

Three wrinkles to remember when wiring pre-orders:

- The purchase shows up as `FlowOutcome.Pending` until Play fulfills it on the release date — **do not grant entitlement on that pending event**. Wait for the matching `OwnedPurchases.Live` update that arrives when fulfillment lands.
- The user can cancel a pending pre-order from the Play Store before the release date. Play surfaces the cancellation server-side via the Voided Purchases API (no client event today). Apps that pre-grant feature access on pre-order signup should reconcile against the Voided Purchases API via their backend before treating the entitlement as durable.
- One product can carry both pre-order and standard offers simultaneously. The `isPreorder` predicate is how you branch the selector.
