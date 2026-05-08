# Granting entitlement: multi-quantity

Play supports multi-quantity purchases for consumables (the *Multi-quantity purchases* flag must be enabled per-product in Play Console; the user picks the quantity in the Play purchase dialog). Always grant `purchase.quantity` units, not 1 — and only grant after `handlePurchase` returns `Success`:

```kotlin
private suspend fun handle(purchase: Purchase) {
    when (val r = billing.handlePurchase(purchase, consume = true)) {
        HandlePurchaseResult.Success -> {
            if (purchase.products.contains("coins_pack")) {
                coinWallet.grant(amount = COINS_PER_PACK * purchase.quantity)
            }
        }
        HandlePurchaseResult.AlreadyAcknowledged -> {} // unreachable for consume=true (consumables aren't acked)
        HandlePurchaseResult.NotPurchased -> {} // pending; wait
        HandlePurchaseResult.NotOwned -> {} // Play says not owned — defer to grace/revoke
        is HandlePurchaseResult.Failure -> showError(r.exception.userFacingCategory)
        // never grant on Failure — recovery sweep retries on the next connection
    }
}
```

`purchase.quantity` defaults to `1` so single-unit code keeps working — but ignoring it on a multi-quantity purchase silently under-grants. The library handles the *acknowledgement* side correctly for any quantity (Play's consume API consumes the whole purchase regardless of unit count); only your entitlement-grant code needs the awareness.
