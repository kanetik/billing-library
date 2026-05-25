# Granting entitlement: multi-quantity

Play supports multi-quantity purchases for consumables. The mechanism is entirely Play-Console- and Play-dialog-driven on PBL 9 — there's no client-side `setPurchaseQuantity` knob — but your code still has to read the resulting quantity off the `Purchase` and grant accordingly.

## What the client controls (nothing)

You enable *Multi-quantity purchases* on the product in Play Console. From then on, Play's own purchase dialog renders a quantity picker; the user picks N units; Play processes the whole transaction and returns one `Purchase` whose `purchase.quantity` field carries the unit count. The library wrapper calls `toOneTimeFlowParams(...)` the same way for any quantity — there is no per-launch quantity to set from your code on PBL 9.

## What the client must do (read `purchase.quantity` and grant accordingly)

Always grant `purchase.quantity` units, not 1 — and only grant after `handlePurchase` returns `Success`:

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

`purchase.quantity` defaults to `1` so single-unit code keeps working — but ignoring it on a multi-quantity purchase silently under-grants. The library handles the *acknowledgement* side correctly for any quantity (Play's consume API consumes the whole purchase regardless of unit count); only your wallet-ledger code needs the awareness.

For the broader pattern of tracking consumable balances (where multi-quantity purchases land), see the [Consumables ledger guide](consumables.md).
