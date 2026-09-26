package com.kanetik.billing.entitlement

import com.android.billingclient.api.Purchase
import com.kanetik.billing.FlowOutcome
import com.kanetik.billing.OwnedPurchases
import com.kanetik.billing.PurchaseEvent
import com.kanetik.billing.PurchaseRevoked
import com.kanetik.billing.logging.BillingLogger
import androidx.annotation.AnyThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opt-in entitlement state machine wrapping
 * [com.kanetik.billing.BillingPurchaseUpdatesOwner.observePurchaseUpdates].
 *
 * Centralizes the `(isEntitled, lastConfirmedTs, source) per entitlement key`
 * bookkeeping that every consumer ends up reinventing on top of the raw
 * `PurchaseEvent` stream. Consumers that want a simple
 * `StateFlow<Map<K, EntitlementState>>` they can collect from a ViewModel get
 * one; consumers that need the raw event stream for custom logic continue to
 * use `observePurchaseUpdates()` directly.
 *
 * ## Generic on K
 *
 * `K` is whatever type your app uses to discriminate entitlements:
 *
 *  - **One non-consumable unlock** (e.g. "ad removal"): `K = Unit` with a
 *    `productKeySelector` that returns `Unit` for the matching product ID,
 *    `null` otherwise. The state map collapses to `{Unit -> ...}`; use
 *    [stateFor] with `Unit` for ergonomics.
 *  - **Multiple non-consumable upgrades** (e.g. a game with a "Pro toolkit"
 *    and a separate "Expansion pack" SKU): `K = String` with the product ID,
 *    or an `enum class Entitlement { PRO_TOOLKIT, EXPANSION }`.
 *  - **Multiple consumable wallets** (coins, gems, fuel): tracking the
 *    *wallet balance* is **not** what this cache does — see the
 *    [Consumables ledger guide](https://kanetik.github.io/billing-library/guides/consumables/).
 *    The cache is for boolean "user is currently entitled to feature X"
 *    state. Each consumable purchase is a wallet credit (see [handleObservation]'s
 *    docs), not an entitlement.
 *
 * The cache holds one [EntitlementState] per key in [state]. Keys absent from
 * the map are implicitly [EntitlementState.Revoked] (the cache hasn't observed
 * a granting purchase for them).
 *
 * ## What it does
 *
 *  - Hydrates from a consumer-provided [EntitlementStorage] (one snapshot per
 *    key) so gated UI can render before the first network round-trip lands.
 *  - Treats [OwnedPurchases.Live] and [OwnedPurchases.Recovered] as grant-only
 *    signals: for each [Purchase] in `purchaseState == PURCHASED`, applies
 *    [productKeySelector]; a non-null result transitions that key to
 *    [EntitlementState.Granted]. A non-match does **not** revoke (Live can
 *    carry empty/UNSPECIFIED_STATE callbacks; Recovered emits only the unacked
 *    subset). Revocation flows through [PurchaseRevoked] — see "Sealed-when
 *    handling" below.
 *  - [FlowOutcome.Failure] is a no-op: existing Granted keys are left
 *    untouched. `Failure` carries no product id, so the cache can't tell
 *    which key's purchase attempt failed.
 *  - Persists every confirmed observation through [EntitlementStorage.write]
 *    so the next process can hydrate.
 *
 * ## Hydration staleness
 *
 * On [start], the cache hydrates from [storage] and trusts each persisted
 * Granted snapshot indefinitely — there is no max-age check at hydration
 * time. A Granted snapshot persisted six months ago in a previous session
 * still hydrates as Granted today. This is intentional: the cache treats
 * Play as the authoritative source of revocation signals, not the local
 * clock. Two things can invalidate a stale-but-hydrated Granted state for
 * a given key:
 *
 *  1. A [PurchaseRevoked] event whose `purchaseToken` matches the cached
 *     snapshot's `purchaseToken` for that key — consumers wire this through
 *     `emitExternalRevocation` from their RTDN/FCM pipeline; this is the
 *     primary revocation channel.
 *  2. Consumer-side max-age before passing the snapshot to the cache —
 *     apps that need a hard ceiling (e.g., "any snapshot older than 90
 *     days is suspicious") can implement that in their [EntitlementStorage]
 *     `readAll()` by filtering out snapshots whose `confirmedAtMs` is
 *     too old. The cache treats absent keys as "no prior snapshot" and
 *     starts them in default Revoked.
 *
 * The deliberate omission of a built-in max-age policy avoids picking an
 * arbitrary number that wouldn't fit every app's threat model.
 *
 * ## What it does *not* do
 *
 *  - It does not pick a persistence library. You implement
 *    [EntitlementStorage] against your own DataStore / signed prefs / Room /
 *    whatever.
 *  - It does not call [com.kanetik.billing.BillingActions.handlePurchase] —
 *    acknowledge / consume + entitlement grant are still your code's job.
 *    The cache only tracks the observation; it doesn't drive the side
 *    effects.
 *  - It does not verify purchase signatures. Pair with
 *    [com.kanetik.billing.security.PurchaseVerifier] in your collector if
 *    you need that — the cache treats every passing selector match as
 *    authoritative.
 *  - It does not track consumable wallet balances. See the
 *    [Consumables ledger guide](https://kanetik.github.io/billing-library/guides/consumables/).
 *
 * ## Wiring
 *
 * ```
 * // Single binary entitlement (ad removal):
 * class AdRemovalViewModel(
 *     billing: BillingRepository,
 *     storage: EntitlementStorage<Unit>,
 * ) : ViewModel() {
 *     private val cache = EntitlementCache(
 *         purchasesUpdates = billing.observePurchaseUpdates(),
 *         storage = storage,
 *         productKeySelector = { p -> if (p.products.contains("ad_removal")) Unit else null },
 *     )
 *     init { viewModelScope.launch { cache.start(viewModelScope) } }
 *     val adsRemoved: StateFlow<Boolean> = cache.stateFor(Unit)
 *         .map { it is EntitlementState.Granted }
 *         .stateIn(viewModelScope, SharingStarted.Eagerly, false)
 * }
 * ```
 *
 * ```
 * // Multi-entitlement (game with non-consumable upgrades):
 * enum class GameEntitlement { PRO_TOOLKIT, EXPANSION_PACK }
 *
 * class ShopViewModel(
 *     billing: BillingRepository,
 *     storage: EntitlementStorage<GameEntitlement>,
 * ) : ViewModel() {
 *     private val cache = EntitlementCache(
 *         purchasesUpdates = billing.observePurchaseUpdates(),
 *         storage = storage,
 *         productKeySelector = { p ->
 *             when {
 *                 "pro_toolkit" in p.products -> GameEntitlement.PRO_TOOLKIT
 *                 "expansion_pack" in p.products -> GameEntitlement.EXPANSION_PACK
 *                 else -> null   // consumables (currency packs) bypass the cache
 *             }
 *         },
 *     )
 *     init { viewModelScope.launch { cache.start(viewModelScope) } }
 *
 *     val hasProToolkit: Flow<Boolean> = cache.stateFor(GameEntitlement.PRO_TOOLKIT)
 *         .map { it is EntitlementState.Granted }
 * }
 * ```
 *
 * Pair with [com.kanetik.billing.lifecycle.BillingConnectionLifecycleManager]
 * (or your own `connectToBilling()` collector) so the underlying connection
 * is open and the recovery sweep can fire — `observePurchaseUpdates()` alone
 * does not hold the connection.
 *
 * ## Sealed-when handling
 *
 * The cache reacts to four event paths:
 *  - [OwnedPurchases.Live] / [OwnedPurchases.Recovered]: **grant-only**.
 *    For each PURCHASED-state purchase, [productKeySelector] is applied; a
 *    non-null result transitions that key to [EntitlementState.Granted] and
 *    persists. A non-match (selector returns null) does **not** revoke —
 *    `Live` can carry `UNSPECIFIED_STATE` entries or products unrelated to
 *    any tracked entitlement, and `Recovered` only emits the
 *    `PURCHASED && !isAcknowledged` subset filtered against the library's
 *    acknowledgedTokens set, so an already-acked entitling purchase will
 *    never appear there. Treating either as authoritative for revocation
 *    would falsely revoke users whose entitling purchase has already been
 *    acknowledged.
 *  - [com.kanetik.billing.PurchaseRevoked]: when `event.purchaseToken`
 *    matches *any* cached snapshot's `purchaseToken`, that key transitions
 *    to [EntitlementState.Revoked] immediately (no grace; Play has explicitly
 *    revoked). Consumers wire `emitExternalRevocation` against their
 *    RTDN→FCM pipeline.
 *
 * The remaining [FlowOutcome] variants ([FlowOutcome.Pending],
 * [FlowOutcome.Canceled], [FlowOutcome.ItemAlreadyOwned],
 * [FlowOutcome.ItemUnavailable], [FlowOutcome.PaymentDeclined],
 * [FlowOutcome.Failure], [FlowOutcome.UnknownResponse]) are
 * intentionally no-ops here — they don't change owned-purchase state, and a
 * Pending purchase explicitly must not grant entitlement (per Play's rules).
 *
 * @param purchasesUpdates The hot purchase-update stream — typically
 *   [com.kanetik.billing.BillingPurchaseUpdatesOwner.observePurchaseUpdates].
 *   The cache subscribes inside [start].
 * @param storage Persistence layer. See [EntitlementStorage] for the contract.
 * @param productKeySelector Maps each [Purchase] to the entitlement key it
 *   grants, or `null` if the purchase doesn't grant any tracked entitlement.
 *   Typical shapes:
 *   `{ p -> if ("pro_toolkit" in p.products) Unit else null }` for a single
 *   non-consumable unlock; `{ p -> p.products.firstOrNull()?.takeIf { it in tracked } }`
 *   for a `K = String` map keyed by product ID; a `when` over known SKUs for
 *   an `enum` or sealed-class key. Consumables and any other products you
 *   don't want the cache to track return `null`.
 * @param clock Time source. Defaults to `System.currentTimeMillis`. Inject a
 *   deterministic source in tests so `confirmedAtMs` assertions don't depend
 *   on real time.
 */
public class EntitlementCache<K : Any>(
    private val purchasesUpdates: Flow<PurchaseEvent>,
    private val storage: EntitlementStorage<K>,
    private val productKeySelector: (Purchase) -> K?,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: BillingLogger = BillingLogger.Noop,
) {
    private val _state = MutableStateFlow<Map<K, EntitlementState>>(emptyMap())

    /**
     * The current entitlement state per key. Keys absent from the map are
     * implicitly [EntitlementState.Revoked] (the cache hasn't observed a
     * granting purchase for them). Empty until [start] runs hydration — if
     * storage holds snapshots, [start] populates [state] from them before
     * returning; if storage is empty, [state] stays empty until the first
     * granting purchase is observed.
     */
    public val state: StateFlow<Map<K, EntitlementState>> = _state.asStateFlow()

    /**
     * Convenience: a [Flow] over one specific key's [EntitlementState],
     * de-duped against unchanged values. An absent key surfaces as
     * [EntitlementState.Revoked].
     */
    public fun stateFor(key: K): Flow<EntitlementState> =
        state.map { it[key] ?: EntitlementState.Revoked }.distinctUntilChanged()

    // Most-recent confirmed snapshot per key. Tracked separately from `_state`
    // so a Revoked transition can carry forward the last confirmed
    // purchaseToken without re-querying storage. Mutated only under [mutex].
    private val lastConfirmedSnapshots: MutableMap<K, EntitlementSnapshot> = HashMap()

    // Guards reduce() and start() so concurrent callers (the upstream
    // collector, a test driving reduce() directly, concurrent start() calls)
    // can't race on _state / lastConfirmedSnapshots updates.
    private val mutex = Mutex()

    // The active collection Job from the most recent successful start(), or
    // null if the cache hasn't been started yet (or a prior start was cancelled
    // mid-hydration). Guarded by [mutex] so concurrent start() callers
    // serialise.
    private var collectionJob: Job? = null

    // UNLIMITED so per-key conflation isn't lost across coroutines (CONFLATED
    // would let a later snapshot for key A clobber an in-flight snapshot for
    // key B). [reduce] calls `writeChannel.trySend(key to snapshot)` while
    // holding [mutex] so trySend order matches state-mutation order across
    // coroutines. A single writer coroutine (launched inside [start])
    // consumes the channel and calls [storage.write] serially.
    //
    // Entitlement transitions happen at human pace (PBL events) — unbounded
    // buffer growth is theoretical, with at most a handful of entries per
    // session.
    private val writeChannel = Channel<Pair<K, EntitlementSnapshot>>(Channel.UNLIMITED)

    /**
     * Hydrates from [storage] and begins collecting from [purchasesUpdates]
     * inside [scope]. Suspends until hydration completes, so by the time
     * [start] returns, [state] already reflects the persisted snapshots —
     * callers that read [state] immediately after `start()` returns will see
     * the hydrated map, not the default empty map. A failed read is treated
     * as "no prior snapshots" and logged via [logger]; the cache proceeds
     * with the empty initial state.
     *
     * Returns the parent [Job] orchestrating the upstream collector. Cancel
     * the returned job (or the [scope] itself) to stop the cache.
     *
     * Calling [start] more than once on the same instance is safe and
     * idempotent — concurrent callers serialise on an internal mutex; the
     * first caller does hydration + launch and stores the resulting Job,
     * subsequent callers return that same active Job. If a previous start()
     * was cancelled mid-hydration (or its Job was later cancelled), a fresh
     * start() will retry — the cache isn't left in a permanently-dead state.
     */
    @AnyThread
    public suspend fun start(scope: CoroutineScope): Job = mutex.withLock {
        // If a previous start() succeeded and that Job is still active, hand
        // back the same handle — multiple callers cancelling the same Job is
        // idempotent, and we don't want N collectors racing on
        // purchasesUpdates + N writer coroutines fighting over storage.
        collectionJob?.let { if (it.isActive) return@withLock it }

        // Drain any per-key snapshots left buffered from a prior cancelled
        // start(). UNLIMITED can hold more than one entry, so this loop runs
        // until empty. Without it, snapshots queued just before the previous
        // start()'s scope was cancelled would be picked up by the NEW writer
        // coroutine and persisted *after* hydration, overwriting storage
        // with stale state.
        while (writeChannel.tryReceive().isSuccess) { /* discard */ }

        // Hydrate from storage. Done inside the mutex so a second caller can't
        // observe state.value before the first caller's hydration lands — the
        // second caller blocks here until the first has either established a
        // Job or failed (in which case the second caller retries hydration).
        // Putting it inside the mutex also means events emitted on
        // purchasesUpdates DURING hydration are processed against a
        // fully-hydrated state (reduce() takes the same mutex).
        val initial: Map<K, EntitlementSnapshot> = try {
            storage.readAll()
        } catch (ce: CancellationException) {
            // Re-throw cancellation so structured concurrency works.
            // collectionJob remains null; a future start() retries.
            throw ce
        } catch (e: Throwable) {
            logger.e(
                "EntitlementCache: failed to read snapshots from storage; proceeding with empty initial state",
                e,
            )
            emptyMap()
        }
        lastConfirmedSnapshots.clear()
        if (initial.isNotEmpty()) {
            lastConfirmedSnapshots.putAll(initial)
            _state.value = initial.mapValues { (_, snap) ->
                if (snap.isEntitled) EntitlementState.Granted else EntitlementState.Revoked
            }
        } else {
            _state.value = emptyMap()
        }

        // Single-writer coroutine consumes [writeChannel] in send order so
        // persistence is serialised AND a slow consumer storage can't stall
        // the upstream collector. Wrapped in supervisorScope so a writer
        // failure doesn't cancel the upstream collector. The writer's
        // lifecycle is bound to the upstream collect: if purchasesUpdates
        // ever completes normally, we cancel it so this start() Job can finish.
        val job = scope.launch {
            supervisorScope {
                val writerJob = launch { runStorageWriter() }
                try {
                    purchasesUpdates.collect { event ->
                        reduce(event)
                    }
                } finally {
                    writerJob.cancel()
                }
            }
        }
        collectionJob = job
        job
    }

    /**
     * Writer coroutine: drains [writeChannel] in FIFO order and serially
     * calls [storage.write] on each (key, snapshot) pair. Failures are logged
     * + absorbed (per the "unwritable storage shouldn't crash the cache"
     * policy); CancellationException propagates so structured concurrency
     * works.
     */
    private suspend fun runStorageWriter() {
        for ((key, snapshot) in writeChannel) {
            try {
                storage.write(key, snapshot)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                if (snapshot.isEntitled) {
                    logger.e(
                        "EntitlementCache: failed to write Granted snapshot to storage for key=$key",
                        e,
                    )
                } else {
                    // Failure to persist Revoked is more serious than failure
                    // to persist Granted — the next process start could
                    // hydrate as Granted from the stale snapshot.
                    logger.e(
                        "EntitlementCache: failed to write Revoked snapshot to storage for key=$key; " +
                            "next process start may hydrate as Granted from stale snapshot",
                        e,
                    )
                }
            }
        }
    }

    // Visible for tests + the start() collector. Synchronised on `mutex` so
    // concurrent callers serialise on state updates. Storage writes are
    // queued via [writeChannel] (UNLIMITED) and drained by
    // [runStorageWriter]; a slow consumer EntitlementStorage (DataStore,
    // encrypted prefs, etc.) can't stall the upstream collector because
    // trySend never suspends.
    internal suspend fun reduce(event: PurchaseEvent) {
        mutex.withLock {
            val toPersist: List<Pair<K, EntitlementSnapshot>> = when (event) {
                is OwnedPurchases.Live -> handleObservation(event.purchases)
                is OwnedPurchases.Recovered -> handleObservation(event.purchases)
                is PurchaseRevoked -> handleRevoked(event)
                is FlowOutcome.Pending,
                is FlowOutcome.Canceled,
                is FlowOutcome.ItemAlreadyOwned,
                is FlowOutcome.ItemUnavailable,
                is FlowOutcome.PaymentDeclined,
                is FlowOutcome.Failure,
                is FlowOutcome.UnknownResponse -> {
                    // No-op. Pending must not grant entitlement (Play's rules).
                    // Canceled / ItemAlreadyOwned / ItemUnavailable / PaymentDeclined
                    // carry no owned-purchase signal that should mutate cache
                    // state. UnknownResponse is reserved for codes PBL doesn't
                    // document — log/observe at the consumer layer if needed.
                    emptyList()
                }
            }
            for ((key, snapshot) in toPersist) {
                writeChannel.trySend(key to snapshot)
            }
        }
    }

    /**
     * Maps PURCHASED-state purchases through [productKeySelector] and
     * transitions each matching key to Granted. Multiple purchases in one
     * event mapping to the same key (rare — Play doesn't issue duplicates,
     * but multi-quantity replays or odd test fixtures could) collapse to the
     * first match's snapshot; subsequent matches for the same key are
     * skipped.
     *
     * Consumables: this method confirms entitlement for the *key* the
     * consumable purchase selector returns — which is typically `null` for
     * consumables, because a consumable purchase is a wallet credit, not an
     * entitlement. See the
     * [Consumables ledger guide](https://kanetik.github.io/billing-library/guides/consumables/)
     * for the recommended wallet-on-the-side pattern.
     */
    private fun handleObservation(purchases: List<Purchase>): List<Pair<K, EntitlementSnapshot>> {
        // Filter to PURCHASED state before applying productKeySelector. PBL's
        // OK callback (which the listener routes to OwnedPurchases.Live) can
        // include rare UNSPECIFIED_STATE entries; granting entitlement on
        // those would be premature — entitlement belongs to actually-purchased
        // state. Pending purchases are a separate event (FlowOutcome.Pending)
        // and never reach this code path.
        //
        // No-match cases (both Live and Recovered) intentionally do NOT
        // revoke. Live can carry UNSPECIFIED_STATE entries or products
        // unrelated to any tracked entitlement, and Recovered only emits the
        // PURCHASED && !isAcknowledged subset of owned purchases (filtered
        // against the library's acknowledgedTokens set per the dedupe in
        // BillingClientStorage). Treating either as authoritative for
        // revocation would falsely revoke users whose entitling purchase has
        // already been acknowledged.
        //
        // Revocation in this cache flows through [PurchaseRevoked] instead —
        // the consumer pushes an explicit revocation signal via
        // emitExternalRevocation (typically decoded from RTDN→FCM payloads).
        val now = clock()
        val results = ArrayList<Pair<K, EntitlementSnapshot>>()
        val seen = HashSet<K>()
        for (purchase in purchases) {
            if (purchase.purchaseState != Purchase.PurchaseState.PURCHASED) continue
            val key = productKeySelector(purchase) ?: continue
            if (!seen.add(key)) continue
            val snapshot = EntitlementSnapshot(
                isEntitled = true,
                confirmedAtMs = now,
                purchaseToken = purchase.purchaseToken,
            )
            results.add(key to transitionToGranted(key, snapshot))
        }
        return results
    }

    /**
     * Matches the revoked `purchaseToken` against every cached snapshot. At
     * most one key will match (Play guarantees tokens are unique per purchase).
     * If a match is found, that key transitions to Revoked immediately (no
     * grace — Play has explicitly revoked).
     */
    private fun handleRevoked(event: PurchaseRevoked): List<Pair<K, EntitlementSnapshot>> {
        // Two-pass: collect matching keys first, then mutate via
        // transitionToRevoked. Updating lastConfirmedSnapshots[key] for an
        // already-present key is technically safe under HashMap's iterator
        // (no structural modification), but the pattern is brittle — a
        // future edit to transitionToRevoked could add a structural change
        // (a new key, a removal) and break iteration. Iterate a snapshot of
        // the matching keys instead so the transition is decoupled from the
        // iteration.
        val now = clock()
        val matchingKeys = lastConfirmedSnapshots
            .filter { (_, confirmed) -> confirmed.purchaseToken == event.purchaseToken }
            .keys.toList()
        val results = ArrayList<Pair<K, EntitlementSnapshot>>(matchingKeys.size)
        for (key in matchingKeys) {
            val snapshot = EntitlementSnapshot(
                isEntitled = false,
                confirmedAtMs = now,
                purchaseToken = event.purchaseToken,
            )
            results.add(key to transitionToRevoked(key, snapshot))
        }
        return results
    }

    /**
     * State-only Granted transition for [key]; returns the snapshot to
     * persist (the caller queues the write).
     */
    private fun transitionToGranted(key: K, snapshot: EntitlementSnapshot): EntitlementSnapshot {
        lastConfirmedSnapshots[key] = snapshot
        _state.value = _state.value + (key to EntitlementState.Granted)
        return snapshot
    }

    /**
     * State-only Revoked transition for [key]; returns [snapshot] so the
     * caller can queue the write.
     */
    private fun transitionToRevoked(key: K, snapshot: EntitlementSnapshot): EntitlementSnapshot {
        lastConfirmedSnapshots[key] = snapshot
        // Set _state immediately so the UI sees Revoked even with slow
        // storage. Persistence happens via the caller queueing onto the
        // write channel.
        _state.value = _state.value + (key to EntitlementState.Revoked)
        return snapshot
    }
}
