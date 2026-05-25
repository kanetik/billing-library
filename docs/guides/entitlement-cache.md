# EntitlementCache (opt-in)

Once your code can answer "did the user buy this" in the moment, three follow-up questions usually land within a few weeks of shipping:

- How do I render gated UI on cold start, *before* the first PBL round-trip lands? (Otherwise paying users see the free-tier UI flicker every launch.)
- What do I do when Play is unreachable for an hour or two: flip every paid user back to the free tier, or wait it out?
- How do I keep my entitlement verdict consistent across process death, configuration changes, and app updates?

PBL doesn't answer any of these. They're consumer concerns built on top of the protocol, which is why most apps end up reinventing the same `(isEntitled, lastConfirmedTimestamp, source) per entitlement` state machine: take the raw `PurchaseEvent` stream, decide which purchases grant which entitlement, persist the verdict so gated UI can render before the first network round-trip, and add a grace window so a transient outage doesn't immediately yank features from a paid user. `EntitlementCache<K>` (in `com.kanetik.billing.entitlement`) is that state machine, opt-in.

It listens to `observePurchaseUpdates()`, so it benefits from the auto-recovery sweep. See [Purchase recovery](purchase-recovery.md) for what `OwnedPurchases.Live` vs `OwnedPurchases.Recovered` actually mean and why you can't just write the callback's `purchases` list to your storage and call it done.

## Choosing K

`K` is whatever type your app uses to discriminate entitlements:

- **One non-consumable unlock** (e.g. "ad removal"): `K = Unit`. The state map collapses to `{Unit -> ...}` and `stateFor(Unit)` gives you a single `Flow<EntitlementState>`.
- **A few non-consumable upgrades** (e.g. a game with a "Pro toolkit" + an "Expansion pack"): `K = String` (product IDs) or `enum class Entitlement { PRO_TOOLKIT, EXPANSION }`.
- **Consumables** (coins, gems, fuel): the cache is **not** the right shape — see the [Consumables ledger guide](consumables.md). Your `productKeySelector` should return `null` for consumable SKUs so they bypass the cache entirely.

Consumables and entitlements often coexist in the same app; the `productKeySelector` is the seam where you tell the cache "ignore these, they're wallet credits."

## Single-entitlement wiring

```kotlin
import com.kanetik.billing.entitlement.EntitlementCache
import com.kanetik.billing.entitlement.EntitlementState
import com.kanetik.billing.entitlement.EntitlementSnapshot
import com.kanetik.billing.entitlement.EntitlementStorage
import com.kanetik.billing.entitlement.GracePolicy
import java.util.concurrent.TimeUnit

class AdRemovalViewModel(
    billing: BillingRepository,
    storage: EntitlementStorage<Unit>, // your impl — see below
) : ViewModel() {

    private val cache = EntitlementCache(
        purchasesUpdates = billing.observePurchaseUpdates(),
        storage = storage,
        gracePolicy = GracePolicy(
            billingUnavailableMs = TimeUnit.HOURS.toMillis(72),
            transientFailureMs   = TimeUnit.HOURS.toMillis(6),
        ),
        productKeySelector = { purchase ->
            if (purchase.products.contains("ad_removal")) Unit else null
        },
    )

    init {
        // start() is suspend so hydration completes before it returns —
        // the first read of cache.state.value reflects the persisted
        // snapshot, not the default empty map. Launch it from viewModelScope
        // so init isn't blocked.
        viewModelScope.launch { cache.start(viewModelScope) }
    }

    val adsRemoved: StateFlow<Boolean> = cache.stateFor(Unit)
        .map { it is EntitlementState.Granted || it is EntitlementState.InGrace }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
```

## Multi-entitlement wiring

```kotlin
enum class GameEntitlement { PRO_TOOLKIT, EXPANSION_PACK }

class ShopViewModel(
    billing: BillingRepository,
    storage: EntitlementStorage<GameEntitlement>,
) : ViewModel() {

    private val cache = EntitlementCache(
        purchasesUpdates = billing.observePurchaseUpdates(),
        storage = storage,
        gracePolicy = GracePolicy(
            billingUnavailableMs = TimeUnit.HOURS.toMillis(72),
            transientFailureMs   = TimeUnit.HOURS.toMillis(6),
        ),
        productKeySelector = { purchase ->
            when {
                "pro_toolkit"    in purchase.products -> GameEntitlement.PRO_TOOLKIT
                "expansion_pack" in purchase.products -> GameEntitlement.EXPANSION_PACK
                else -> null   // consumable currency packs bypass the cache; see the consumables guide
            }
        },
    )

    init { viewModelScope.launch { cache.start(viewModelScope) } }

    val hasProToolkit: Flow<Boolean> = cache.stateFor(GameEntitlement.PRO_TOOLKIT)
        .map { it is EntitlementState.Granted || it is EntitlementState.InGrace }

    val hasExpansion: Flow<Boolean> = cache.stateFor(GameEntitlement.EXPANSION_PACK)
        .map { it is EntitlementState.Granted || it is EntitlementState.InGrace }
}
```

## State

The cache exposes a `StateFlow<Map<K, EntitlementState>>`. Keys absent from the map are implicitly `EntitlementState.Revoked` (the cache hasn't observed a granting purchase for them). Each per-key value is one of three terminal states:

- `Granted` — confirmed entitlement; show the gated UI / unlock the feature.
- `InGrace(expiresAtMs, reason)` — recently confirmed, then a `FlowOutcome.Failure` arrived. Treat as entitled until `expiresAtMs`; after that the cache transitions to `Revoked`. Reason is one of `BillingUnavailable` (Play Services missing, account ineligible, region restriction) or `TransientFailure` (network error, service disconnect, generic billing error).
- `Revoked` — no entitlement; hide the gated UI.

`stateFor(key: K)` returns a `Flow<EntitlementState>` that surfaces absent keys as `Revoked` and is `distinctUntilChanged()` against unchanged values — usually what you want for UI binding.

Grace expiry is re-evaluated on every emission and on a periodic tick, per key, so an extended outage correctly transitions `InGrace → Revoked` even when no further updates arrive in between.

## When to use it

Use `EntitlementCache` when you want a simple `is the user entitled to X right now?` flow off the side of your existing `observePurchaseUpdates()` integration. The cache is purely observational; it does **not** call `handlePurchase` for you. Acknowledge / consume + entitlement grant remain your collector's job. The cache just tracks the resulting confirmed observation.

Skip it if you have your own state machine you're already happy with, or if you need behavior the cache doesn't cover (subscription tier comparison, server-side reconciliation as the source of truth, dynamically-added entitlements). Write a thin custom layer for those.

The cache reacts to four event paths:

- `OwnedPurchases.Live` and `OwnedPurchases.Recovered` are **grant-only**. For each `PURCHASED`-state purchase, `productKeySelector` is applied; a non-null result transitions that key to `Granted` and persists. A *non-match* (selector returns null) does **not** revoke. `Live` can carry `UNSPECIFIED_STATE` entries or products unrelated to any tracked entitlement, and `Recovered` only emits the unacknowledged subset (an already-acked entitlement won't appear in it). Treating either as authoritative for revocation would falsely revoke users with already-acknowledged purchases.
- `FlowOutcome.Failure` triggers `InGrace` for every currently-Granted or InGrace key (or transitions them straight to `Revoked` if the policy window is zero or has already elapsed since that key's last confirmation).
- `PurchaseRevoked` matched against *any* key's `lastConfirmedSnapshot.purchaseToken` transitions that key (and only that key) to `Revoked` immediately (no grace; Play has explicitly revoked the entitlement). Consumers wire `emitExternalRevocation` against their RTDN→FCM pipeline; see [Server-driven revocation](server-driven-revocation.md).

The remaining `FlowOutcome` variants (`Pending`, `Canceled`, `ItemAlreadyOwned`, `ItemUnavailable`, `UnknownResponse`) are no-ops; they don't change owned-purchase state, and `Pending` must not grant entitlement (per Play's rules).

## Storage is your responsibility

The library doesn't pick a persistence library. Implement `EntitlementStorage<K>` against whatever your app already uses (DataStore, EncryptedSharedPreferences, Room, signed prefs against a server-issued key). The interface is two suspend functions:

```kotlin
interface EntitlementStorage<K : Any> {
    suspend fun readAll(): Map<K, EntitlementSnapshot>
    suspend fun write(key: K, snapshot: EntitlementSnapshot)
}
```

Your implementation is responsible for serializing `K` to a stable on-disk identifier. For `String` keys that's the identity; for `enum class` keys use `K.name` (not `toString()` — it's overridable); for sealed classes pick a stable discriminator field. Stability across app upgrades matters — renaming an enum constant breaks the on-disk mapping.

`EntitlementSnapshot` is plain data: `(isEntitled: Boolean, confirmedAtMs: Long, purchaseToken: String?)`. The cache calls `readAll()` once on `start()` to hydrate, then `write(key, snapshot)` on every entitlement-affecting transition. `InGrace` is **not** persisted — grace re-derives from the most recent confirmed `confirmedAtMs` on read, which keeps an attacker who can manipulate storage from extending the window indefinitely.

For most apps the on-device storage is fine: a tampered snapshot gets overwritten the next time `OwnedPurchases.Live` or `OwnedPurchases.Recovered` confirms (or fails to confirm via `PurchaseRevoked`) the entitlement. The cache trusts what storage returns.

## Tamper-resistant storage

If your threat model includes users tampering with on-device storage to extend entitlement (freemium apps where the paid entitlement has real value), wrap your `EntitlementStorage<K>` in `SignedEntitlementStorage<K>`. The decorator signs each snapshot on every write (using a key-aware canonical encoding so cross-key sig swaps fail verification) and verifies the signature on read; tampered snapshots are dropped (the cache reads them as cold-start for that key, and the next `OwnedPurchases.Live` or `OwnedPurchases.Recovered` re-confirms truth).

```kotlin
import com.kanetik.billing.entitlement.signed.*

val storage: EntitlementStorage<GameEntitlement> = SignedEntitlementStorage(
    delegate = MyDataStoreEntitlementStorage(context),
    keyProvider = KeystoreBackedKeyProvider.create(),
    signatureStore = SharedPreferencesSignatureStore(context),
    keyToStorageId = { it.name },   // stable string per K instance
    onTamperDetected = { entitlementKey, event ->
        when (event) {
            TamperEvent.MissingSignature -> logger.info("First read for $entitlementKey")
            TamperEvent.InvalidSignature -> logger.warn("Possible tampering on $entitlementKey")
            is TamperEvent.UnsupportedVersion ->
                logger.warn("Unknown sig version ${event.version} on $entitlementKey")
        }
    },
)
val cache = EntitlementCache(purchasesUpdates, storage, gracePolicy, productKeySelector)
```

`SignedEntitlementStorage` keeps the library neutral on the persistence backend — the underlying `EntitlementStorage` still owns the snapshot bytes, the signature blobs (one per entitlement key) live separately in `SignatureStore`. Pick `KeystoreBackedKeyProvider` (the recommended default; uses Android Keystore HMAC keys, hardware-backed where available) or `ServerSeededKeyProvider` (per-install seed from your backend, cached locally — see its KDoc for the plaintext-cache caveat). The library can't enforce key non-extractability and isn't a replacement for server-side validation; both caveats are documented on the relevant types.

`onTamperDetected` receives the entitlement key (as serialized via `keyToStorageId`) along with the typed `TamperEvent`. A tamper on one key doesn't affect the others — verified snapshots for the unaffected keys still flow through. The most common event in practice is `MissingSignature` — fired the first time the decorator reads a snapshot that was written by a release without `SignedEntitlementStorage`. Treat it differently from `InvalidSignature` in your telemetry; the latter is the strong tamper indicator.

### Migrating an existing unsigned snapshot

By default, the first read after wrapping an existing unsigned snapshot fires `TamperEvent.MissingSignature` and omits that key from the verified map — a one-time cold-start for that entitlement. The next `OwnedPurchases.Live` or `OwnedPurchases.Recovered` confirmation re-establishes truth and writes a signature on the transition. That's the secure default: no perpetual "delete the signature file to bypass" attack window.

If you'd rather avoid the cold-start (UX over strict-from-day-one tamper resistance), call `SignedEntitlementStorage.migrateUnsignedSnapshot` once per entitlement on first launch after upgrade, guarded by your own marker:

```kotlin
suspend fun bootstrapEntitlementStorage(context: Context): EntitlementStorage<GameEntitlement> {
    val rawStorage = MyDataStoreEntitlementStorage(context)
    val keyProvider = KeystoreBackedKeyProvider.create()
    val sigStore = SharedPreferencesSignatureStore(context)

    if (!prefs.getBoolean("entitlement_signed_migrated", false)) {
        for (entitlement in GameEntitlement.values()) {
            SignedEntitlementStorage.migrateUnsignedSnapshot(
                entitlementKey = entitlement.name,
                entitlementCacheKey = entitlement,
                delegate = rawStorage,
                keyProvider = keyProvider,
                signatureStore = sigStore,
            )
        }
        prefs.edit().putBoolean("entitlement_signed_migrated", true).apply()
    }
    return SignedEntitlementStorage(
        delegate = rawStorage,
        keyProvider = keyProvider,
        signatureStore = sigStore,
        keyToStorageId = { it.name },
        onTamperDetected = { _, _ -> },
    )
}
```

Tradeoff: the helper trusts the existing snapshot once. If pre-upgrade tampering is in your threat model, skip the helper and accept the cold-start. The helper itself can't be made into a permanent decorator mode without leaving a "delete the sig file to re-trigger migration" backdoor — that's why it's a static one-shot guarded by consumer-side state.

## Wiring the connection

`EntitlementCache` consumes `observePurchaseUpdates()`, which on its own does not hold the underlying Play Billing connection open. Pair the cache with `BillingConnectionLifecycleManager` (or your own `connectToBilling()` collector) so the connection stays warm and the recovery sweep on connect can fire — that sweep is what produces the `OwnedPurchases.Recovered` events the cache uses to confirm entitlement after process restarts. See [Lifecycle integration](lifecycle.md).
