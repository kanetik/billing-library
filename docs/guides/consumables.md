# Consumables: wallet ledger pattern

Consumable products are different from non-consumable unlocks in a way that matters for how you track them:

- A **non-consumable unlock** ("Pro toolkit", "ad removal") is one purchase that grants permanent access to a feature. The right state machine is `Granted | InGrace | Revoked` — exactly what [`EntitlementCache`](entitlement-cache.md) is built for.
- A **consumable** ("100 coins", "5 gallons of fuel") is a one-shot credit. Each successful purchase adds N units to a wallet; gameplay (or whatever) spends them down; when the wallet is empty, the user buys again — same SKU, fresh purchase, fresh token. There's no "is the user entitled?" state — there's only a running balance.

`EntitlementCache` deliberately does **not** track wallet balances. This page is the pattern for the cache-on-the-side that does.

## What the library handles

The library wrapper takes consumable purchases all the way through Play's acknowledge / consume protocol the same way it handles non-consumables:

- `handlePurchase(purchase, consume = true)` — calls Play's `consumeAsync` (which doubles as the acknowledgement for consumables; Play doesn't separate the two for this product type). Returns `Success` when the consume call landed.
- Recovery sweep — on every successful connection, any unacknowledged consumable purchase that the user paid for but the app never finished consuming gets re-emitted as `OwnedPurchases.Recovered`. Hand it back to `handlePurchase(consume = true)` and grant the wallet units the same way you would for a `Live` purchase. **This is the reason a wallet ledger is your responsibility — only the consumer knows how many units a purchase grants.**

## What you handle: the wallet

Apps that sell consumables maintain a wallet (a `Map<WalletId, Int>`, a single `Long` for one-currency apps, etc.) outside the cache. The wallet:

- Increments on `HandlePurchaseResult.Success` from a consumable purchase, by `purchase.quantity * unitsPerSku[productId]`.
- Decrements when gameplay spends units.
- Persists to your own storage. The library has nothing to say about this — pick DataStore, Room, encrypted prefs, whatever fits.

The library's job ends at "I successfully consumed this purchase with Play; safe for you to grant now"; the wallet's job is everything else.

## Minimal sketch

```kotlin
class CoinWallet(private val storage: WalletStorage) {
    private val _balance = MutableStateFlow(storage.read())
    val balance: StateFlow<Int> = _balance.asStateFlow()

    suspend fun grant(units: Int) {
        val next = _balance.value + units
        storage.write(next)
        _balance.value = next
    }

    suspend fun spend(units: Int): Boolean {
        if (_balance.value < units) return false
        val next = _balance.value - units
        storage.write(next)
        _balance.value = next
        return true
    }
}

class ShopViewModel(
    private val billing: BillingRepository,
    private val wallet: CoinWallet,
) : ViewModel() {
    private val unitsPerSku = mapOf(
        "coins_pack_50"   to 50,
        "coins_pack_500"  to 500,
        "coins_pack_5000" to 5_000,
    )

    init {
        viewModelScope.launch {
            billing.observePurchaseUpdates().collect { event ->
                when (event) {
                    is OwnedPurchases.Live -> event.purchases.forEach { grantOrSkip(it) }
                    is OwnedPurchases.Recovered -> event.purchases.forEach { grantOrSkip(it) }
                    else -> { /* not our concern here */ }
                }
            }
        }
    }

    private suspend fun grantOrSkip(purchase: Purchase) {
        // Find the wallet SKU on this purchase. Returning early on a non-match
        // keeps the wallet branch separate from any EntitlementCache branch
        // your app also runs against the same stream.
        val unitsPer = purchase.products
            .firstNotNullOfOrNull { unitsPerSku[it] } ?: return

        when (val r = billing.handlePurchase(purchase, consume = true)) {
            HandlePurchaseResult.Success ->
                wallet.grant(units = unitsPer * purchase.quantity)
            HandlePurchaseResult.AlreadyAcknowledged -> {
                // Unreachable for consume = true. Consumables aren't acked
                // (they're consumed); short-circuit only fires on the
                // consume = false path. Branch present for exhaustiveness.
            }
            HandlePurchaseResult.NotPurchased -> {
                // Pending — wait for the matching Live update that arrives
                // when payment confirms. The library re-delivers it.
            }
            HandlePurchaseResult.NotOwned -> {
                // Stale snapshot — Play says this purchase isn't owned. Defer
                // to your reconciliation logic; do not credit the wallet.
            }
            is HandlePurchaseResult.Failure -> {
                // Don't grant. The recovery sweep re-emits the unacknowledged
                // purchase on the next connect for retry.
            }
        }
    }
}
```

Three things to call out about this shape:

- **`purchase.quantity` matters.** Read it on every grant. Defaults to `1` so single-unit code stays correct, but ignoring it on a multi-quantity purchase silently under-credits. See [Multi-quantity purchases](multi-quantity.md).
- **Only grant on `Success`.** Crediting on `Failure` and "fixing it later" leaves you with phantom currency the user didn't really pay for; the [Purchase recovery](purchase-recovery.md) sweep retries acked-less purchases on the next connect, so transient failures resolve themselves.
- **Idempotency.** Each successful `consumeAsync` produces one `Success`. Multiple `Recovered` snapshots can carry the same purchase token before Play marks it acknowledged, but the library's internal acked-token filter (see [Purchase recovery](purchase-recovery.md)) prevents re-delivery once the consume lands. If you want belt-and-suspenders, store the last-credited token in your wallet and skip duplicates explicitly.

## Mixing wallets with `EntitlementCache`

The two coexist on the same `observePurchaseUpdates()` stream:

- Wallet path: branch on consumable product IDs; call `handlePurchase(consume = true)`; credit the wallet on `Success`.
- Cache path: pass a `productKeySelector` that returns the entitlement key only for non-consumable SKUs and `null` otherwise. The cache then ignores consumable purchases — they don't grant any tracked entitlement.

The `productKeySelector` example in [`EntitlementCache`](entitlement-cache.md) shows this split — consumable currency packs `return null` so they never end up in the cache's state map, while non-consumable unlocks map to a key.

## What about server-side?

The library has nothing to do with server-side wallet management — that's deliberately a separate concern. If your app awards wallet credits on the server (you have a backend and want it to be the source of truth):

- Have the server consume the Purchase via `Purchases.products:consume` on the Play Developer API.
- Skip `handlePurchase(consume = true)` on the client for that SKU.
- Let your server-issued FCM (or whatever) push the updated balance into the client wallet.

The two paths shouldn't both consume the same purchase — Play accepts the first consume and `ITEM_NOT_OWNED`s the second, which the library surfaces as `HandlePurchaseResult.NotOwned`. Either pick client-driven consume (this guide) or server-driven consume (your backend's call) per SKU; don't mix on the same one.
