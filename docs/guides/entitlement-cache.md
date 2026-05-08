# EntitlementCache (opt-in)

Once your code can answer "did the user buy this" in the moment, three follow-up questions usually land within a few weeks of shipping:

- How do I render premium UI on cold start, *before* the first PBL round-trip lands? (Otherwise paying users see the free-tier UI flicker every launch.)
- What do I do when Play is unreachable for an hour or two — flip every paid user back to the free tier, or wait it out?
- How do I keep my entitlement verdict consistent across process death, configuration changes, and app updates?

PBL doesn't answer any of these. They're consumer concerns built on top of the protocol, which is why most apps end up reinventing the same `(isEntitled, lastConfirmedTimestamp, source)` state machine: take the raw `PurchaseEvent` stream, decide which purchases grant a given entitlement, persist the verdict so premium UI can render before the first network round-trip, and add a grace window so a transient outage doesn't immediately yank features from a paid user. `EntitlementCache` (in `com.kanetik.billing.entitlement`) is that state machine, opt-in.

It listens to `observePurchaseUpdates()`, so it benefits from the auto-recovery sweep — see [Purchase recovery](purchase-recovery.md) for what `OwnedPurchases.Live` vs `OwnedPurchases.Recovered` actually mean and why you can't just write the callback's `purchases` list to your storage and call it done.

```kotlin
import com.kanetik.billing.entitlement.EntitlementCache
import com.kanetik.billing.entitlement.EntitlementState
import com.kanetik.billing.entitlement.EntitlementSnapshot
import com.kanetik.billing.entitlement.EntitlementStorage
import com.kanetik.billing.entitlement.GracePolicy
import java.util.concurrent.TimeUnit

class PremiumViewModel(
    billing: BillingRepository,
    storage: EntitlementStorage, // your impl — see below
) : ViewModel() {

    private val cache = EntitlementCache(
        purchasesUpdates = billing.observePurchaseUpdates(),
        storage = storage,
        gracePolicy = GracePolicy(
            billingUnavailableMs = TimeUnit.HOURS.toMillis(72),
            transientFailureMs   = TimeUnit.HOURS.toMillis(6),
        ),
        productPredicate = { it.products.contains("premium_lifetime") },
    )

    init {
        // start() is suspend so hydration completes before it returns —
        // the first read of cache.state.value reflects the persisted
        // snapshot, not the default Revoked. Launch it from viewModelScope
        // so init isn't blocked.
        viewModelScope.launch { cache.start(viewModelScope) }
    }

    val isPremium: StateFlow<Boolean> = cache.state
        .map { it !is EntitlementState.Revoked }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
}
```

The cache exposes a `StateFlow<EntitlementState>` with three terminal states:

- `Granted` — confirmed entitlement; show premium UI.
- `InGrace(expiresAtMs, reason)` — recently confirmed, then a `FlowOutcome.Failure` arrived. Treat as entitled until `expiresAtMs`; after that the cache transitions to `Revoked`. Reason is one of `BillingUnavailable` (Play Services missing, account ineligible, region restriction) or `TransientFailure` (network error, service disconnect, generic billing error).
- `Revoked` — no entitlement; hide premium UI.

Grace expiry is re-evaluated on every emission and on a periodic tick, so an extended outage correctly transitions `InGrace → Revoked` even when no further updates arrive in between.

## When to use it

Use `EntitlementCache` when you want a simple `is the user entitled right now?` flow off the side of your existing `observePurchaseUpdates()` integration. The cache is purely observational — it does **not** call `handlePurchase` for you. Acknowledge / consume + entitlement grant remain your collector's job; the cache just tracks the resulting confirmed observation.

Skip it if you have your own state machine you're already happy with, or if you need behavior the cache doesn't cover (subscription tier comparison, server-side reconciliation as the source of truth, multi-entitlement dispatch — write a thin custom layer for those).

The cache reacts to four event paths:

- `OwnedPurchases.Live` and `OwnedPurchases.Recovered` are **grant-only**. A match against the cache's `productPredicate` transitions to `Granted` and persists the snapshot. A *non-match* on either does **not** revoke — `Live` can carry `UNSPECIFIED_STATE` entries or products unrelated to the predicate, and `Recovered` only emits the unacknowledged subset (an already-acked entitlement won't appear in it). Treating either as authoritative for revocation would falsely revoke users with already-acknowledged purchases.
- `FlowOutcome.Failure` triggers `InGrace` (or transitions straight to `Revoked` if the policy window is zero or has already elapsed since the last confirmation).
- `PurchaseRevoked` matched against `lastConfirmedSnapshot.purchaseToken` transitions to `Revoked` immediately (no grace; Play has explicitly revoked the entitlement). Consumers wire `emitExternalRevocation` against their RTDN→FCM pipeline (or whatever transport carries refund/chargeback signals); see [Server-driven revocation](server-driven-revocation.md).

The remaining `FlowOutcome` variants (`Pending`, `Canceled`, `ItemAlreadyOwned`, `ItemUnavailable`, `UnknownResponse`) are no-ops — they don't change owned-purchase state, and `Pending` must not grant entitlement (per Play's rules).

## Storage is your responsibility

The library doesn't pick a persistence library. Implement `EntitlementStorage` against whatever your app already uses — DataStore, EncryptedSharedPreferences, Room, signed prefs against a server-issued key, etc. The interface is two suspend functions:

```kotlin
interface EntitlementStorage {
    suspend fun read(): EntitlementSnapshot?
    suspend fun write(snapshot: EntitlementSnapshot)
}
```

`EntitlementSnapshot` is plain data: `(isEntitled: Boolean, confirmedAtMs: Long, purchaseToken: String?)`. The cache calls `read()` once on `start()` to hydrate, then `write()` on every entitlement-affecting transition. `InGrace` is **not** persisted — grace re-derives from the most recent confirmed `confirmedAtMs` on read, which keeps an attacker who can manipulate storage from extending the window indefinitely.

For most apps the on-device storage is fine — a tampered snapshot gets overwritten the next time `OwnedPurchases.Live` or `OwnedPurchases.Recovered` confirms (or fails to confirm via `PurchaseRevoked`) the entitlement. The cache trusts what storage returns.

## Tamper-resistant storage

If your threat model includes users tampering with on-device storage to extend entitlement (freemium apps where premium has real value), wrap your `EntitlementStorage` in `SignedEntitlementStorage`. The decorator signs the snapshot on every write and verifies the signature on read; tampered snapshots are dropped (the cache reads them as cold-start and the next `OwnedPurchases.Live` or `OwnedPurchases.Recovered` re-confirms truth).

```kotlin
import com.kanetik.billing.entitlement.signed.*

val storage: EntitlementStorage = SignedEntitlementStorage(
    delegate = MyDataStoreEntitlementStorage(context),
    keyProvider = KeystoreBackedKeyProvider.create(),
    signatureStore = SharedPreferencesSignatureStore(context),
    onTamperDetected = { event ->
        when (event) {
            TamperEvent.MissingSignature -> logger.info("First read after upgrade")
            TamperEvent.InvalidSignature -> logger.warn("Possible tampering detected")
            is TamperEvent.UnsupportedVersion -> logger.warn("Unknown sig version ${event.version}")
        }
    },
)
val cache = EntitlementCache(purchasesUpdates, storage, gracePolicy, productPredicate)
```

`SignedEntitlementStorage` keeps the library neutral on the persistence backend — the underlying `EntitlementStorage` still owns the snapshot bytes, the signature blob lives separately in `SignatureStore`. Pick `KeystoreBackedKeyProvider` (the recommended default; uses Android Keystore HMAC keys, hardware-backed where available) or `ServerSeededKeyProvider` (per-install seed from your backend, cached locally — see its KDoc for the plaintext-cache caveat). The library can't enforce key non-extractability and isn't a replacement for server-side validation; both caveats are documented on the relevant types.

`onTamperDetected` distinguishes three cases via the `TamperEvent` sealed type. The most common one in practice is `MissingSignature` — fired the first time the decorator reads a snapshot that was written by a release without `SignedEntitlementStorage`. Treat it differently from `InvalidSignature` in your telemetry; the latter is the strong tamper indicator.

### Migrating an existing unsigned snapshot

By default, the first read after wrapping an existing unsigned snapshot fires `TamperEvent.MissingSignature` and returns null — a one-time cold-start. The next `OwnedPurchases.Live` or `OwnedPurchases.Recovered` confirmation re-establishes truth and writes a signature on the transition. That's the secure default: no perpetual "delete the signature file to bypass" attack window.

If you'd rather avoid the cold-start (UX over strict-from-day-one tamper resistance), call `SignedEntitlementStorage.migrateUnsignedSnapshot` once on first launch after upgrade, guarded by your own marker. `migrateUnsignedSnapshot` is `suspend`, so call it from a coroutine — and call it **before** constructing the `SignedEntitlementStorage` that wraps the same trio, so the cache's first read sees the signature you just wrote:

```kotlin
suspend fun bootstrapEntitlementStorage(context: Context): EntitlementStorage {
    val rawStorage = MyDataStoreEntitlementStorage(context)
    val keyProvider = KeystoreBackedKeyProvider.create()
    val sigStore = SharedPreferencesSignatureStore(context)

    if (!prefs.getBoolean("entitlement_signed_migrated", false)) {
        SignedEntitlementStorage.migrateUnsignedSnapshot(rawStorage, keyProvider, sigStore)
        prefs.edit().putBoolean("entitlement_signed_migrated", true).apply()
    }
    return SignedEntitlementStorage(rawStorage, keyProvider, sigStore, onTamperDetected = { ... })
}
```

Tradeoff: the helper trusts the existing snapshot once. If pre-upgrade tampering is in your threat model, skip the helper and accept the cold-start. The helper itself can't be made into a permanent decorator mode without leaving a "delete the sig file to re-trigger migration" backdoor — that's why it's a static one-shot guarded by consumer-side state.

## Wiring the connection

`EntitlementCache` consumes `observePurchaseUpdates()`, which on its own does not hold the underlying Play Billing connection open. Pair the cache with `BillingConnectionLifecycleManager` (or your own `connectToBilling()` collector) so the connection stays warm and the recovery sweep on connect can fire — that sweep is what produces the `OwnedPurchases.Recovered` events the cache uses to confirm entitlement after process restarts. See [Lifecycle integration](lifecycle.md).
