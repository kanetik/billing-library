# Pre-order / multi-offer one-time products

Play Billing lets a one-time product carry multiple offers (e.g., a pre-order discount alongside the regular price). The `toOneTimeFlowParams` extension takes an optional selector:

```kotlin
val params = productDetails.toOneTimeFlowParams(
    offerSelector = { offers ->
        offers.firstOrNull { it.offerId?.contains("preorder") == true }
            ?: offers.firstOrNull()
    }
)
```

Default selector picks the first offer. If your app has only one offer per product, you can ignore the parameter.
