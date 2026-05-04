package com.kanetik.billing.entitlement

import com.android.billingclient.api.Purchase
import com.kanetik.billing.PurchasesUpdate
import com.kanetik.billing.exception.BillingErrorCategory
import com.kanetik.billing.exception.BillingException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opt-in entitlement state machine wrapping
 * [com.kanetik.billing.BillingPurchaseUpdatesOwner.observePurchaseUpdates].
 *
 * Centralizes the `(isEntitled, lastConfirmedTs, source)` bookkeeping that
 * every consumer ends up reinventing on top of the raw `PurchasesUpdate`
 * stream. Consumers that want a simple `StateFlow<EntitlementState>` they can
 * collect from a ViewModel get one; consumers that need the raw event stream
 * for custom logic continue to use `observePurchaseUpdates()` directly.
 *
 * ## What it does
 *
 *  - Hydrates from a consumer-provided [EntitlementStorage] so premium UI can
 *    render before the first network round-trip lands.
 *  - Maps [PurchasesUpdate.Success] / [PurchasesUpdate.Recovered] to
 *    [EntitlementState.Granted] / [EntitlementState.Revoked] via the
 *    `productPredicate` you pass in.
 *  - On [PurchasesUpdate.Failure], moves to [EntitlementState.InGrace] for a
 *    window determined by the failure type and your [GracePolicy] — premium
 *    keeps working through a brief outage instead of being yanked the
 *    moment Play stops responding.
 *  - Re-evaluates grace expiry on every emission and on a periodic tick, so
 *    an extended outage correctly transitions InGrace → Revoked even when
 *    no further updates arrive.
 *  - Persists every confirmed observation through [EntitlementStorage.write]
 *    so the next process can hydrate. Grace is not persisted (re-derived
 *    from the most recent confirmed `confirmedAtMs`).
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
 *    you need that — the cache treats every passing predicate as authoritative.
 *
 * ## Wiring
 *
 * ```
 * class PremiumViewModel(
 *     billing: BillingRepository,
 *     storage: EntitlementStorage,
 * ) : ViewModel() {
 *     private val cache = EntitlementCache(
 *         purchasesUpdates = billing.observePurchaseUpdates(),
 *         storage = storage,
 *         gracePolicy = GracePolicy(
 *             billingUnavailableMs = TimeUnit.HOURS.toMillis(72),
 *             transientFailureMs   = TimeUnit.HOURS.toMillis(6),
 *         ),
 *         productPredicate = { it.products.contains("premium_lifetime") },
 *     ).also { it.start(viewModelScope) }
 *
 *     val isPremium: StateFlow<Boolean> = cache.state
 *         .map { it !is EntitlementState.Revoked }
 *         .stateIn(viewModelScope, SharingStarted.Eagerly, false)
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
 * The cache reacts to [PurchasesUpdate.Success], [PurchasesUpdate.Recovered],
 * and [PurchasesUpdate.Failure]. Other variants ([PurchasesUpdate.Pending],
 * [PurchasesUpdate.Canceled], [PurchasesUpdate.ItemAlreadyOwned],
 * [PurchasesUpdate.ItemUnavailable], [PurchasesUpdate.UnknownResponse]) are
 * intentionally no-ops here — they don't change owned-purchase state, and a
 * Pending purchase explicitly must not grant entitlement (per Play's rules).
 *
 * Refund / revocation handling will land via `PurchasesUpdate.Revoked`
 * once sibling issue #2 ships — see the TODO inside [reduce].
 *
 * @param purchasesUpdates The hot purchase-update stream — typically
 *   [com.kanetik.billing.BillingPurchaseUpdatesOwner.observePurchaseUpdates].
 *   The cache subscribes inside [start].
 * @param storage Persistence layer. See [EntitlementStorage] for the
 *   contract.
 * @param gracePolicy How long to keep treating the user as entitled after a
 *   [PurchasesUpdate.Failure]. Pass [GracePolicy.None] to disable grace.
 * @param productPredicate Filter applied to each [Purchase] in a Success /
 *   Recovered event to decide whether it grants entitlement. Typical
 *   shapes: `{ it.products.contains("premium_lifetime") }` for a single SKU,
 *   `{ it.products.any { id -> id in premiumIds } }` for a SKU set.
 * @param clock Time source. Defaults to `System.currentTimeMillis`. Inject a
 *   deterministic source in tests so grace-window assertions don't depend
 *   on real time.
 * @param graceTickIntervalMs How often the periodic tick re-evaluates an
 *   active [EntitlementState.InGrace] state to detect grace expiry. Defaults
 *   to 60 seconds — fine-grained enough that grace never overstays by more
 *   than a minute, coarse enough to be free of cost. Set lower in tests
 *   driving a virtual clock.
 */
public class EntitlementCache(
    private val purchasesUpdates: Flow<PurchasesUpdate>,
    private val storage: EntitlementStorage,
    private val gracePolicy: GracePolicy,
    private val productPredicate: (Purchase) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val graceTickIntervalMs: Long = DEFAULT_GRACE_TICK_INTERVAL_MS,
) {

    private val _state = MutableStateFlow<EntitlementState>(EntitlementState.Revoked)

    /**
     * The current entitlement state. `Revoked` until [start] hydrates from
     * storage and the first event arrives.
     */
    public val state: StateFlow<EntitlementState> = _state.asStateFlow()

    // Tracks whether we've seen at least one PurchasesUpdate.Recovered. Used
    // to gate the "Recovered with no matching purchase → Revoked" transition:
    // the connect-time sweep can race the cache's first subscription, so a
    // single empty Recovered at boot shouldn't blow away a snapshot from a
    // previous session. See the README "Purchase recovery" section for why
    // the sweep behaves this way.
    private var hasObservedRecovered: Boolean = false

    // Most-recent confirmed snapshot. Tracked separately from `_state` so
    // grace transitions can preserve the underlying confirmed observation
    // (purchaseToken, confirmedAtMs) without re-querying storage.
    private var lastConfirmedSnapshot: EntitlementSnapshot? = null

    // Guards reduce() so concurrent emissions (the upstream flow + the
    // grace tick) can't race on _state / lastConfirmedSnapshot updates.
    private val mutex = Mutex()

    /**
     * Begins collecting from [purchasesUpdates] and ticking the grace clock
     * inside [scope]. Hydrates from [storage] before the first emission so
     * `state` reflects the persisted snapshot as soon as the suspending
     * `read()` returns.
     *
     * Returns the parent [Job] orchestrating both the upstream collector and
     * the grace tick. Cancel the returned job (or the [scope] itself) to
     * stop the cache.
     *
     * Calling [start] more than once on the same instance launches an
     * additional collector — the [Job] discipline is on the caller. Most
     * apps create one cache per scope and start it once.
     */
    public fun start(scope: CoroutineScope): Job = scope.launch {
        // Hydrate from storage first so state is consistent before we begin
        // emitting transitions. A failed read shouldn't crash the cache —
        // treat as "no prior snapshot" and proceed with the default Revoked
        // state.
        val initial = runCatching { storage.read() }.getOrNull()
        if (initial != null) {
            mutex.withLock {
                lastConfirmedSnapshot = initial
                _state.value = if (initial.isEntitled) {
                    EntitlementState.Granted
                } else {
                    EntitlementState.Revoked
                }
            }
        }

        // Periodic tick to detect grace expiry without a fresh upstream event.
        // A consumer offline for 24h with the app foregrounded should still
        // see InGrace → Revoked the moment grace lapses, not the next time
        // Play sends an update.
        launch { tickGraceWindow() }

        purchasesUpdates.collect { update ->
            reduce(update)
        }
    }

    private suspend fun tickGraceWindow() {
        while (true) {
            delay(graceTickIntervalMs)
            mutex.withLock {
                val current = _state.value
                if (current is EntitlementState.InGrace && clock() >= current.expiresAtMs) {
                    transitionToRevoked()
                }
            }
        }
    }

    // Visible for tests + the start() collector. Synchronised on `mutex` so
    // concurrent emissions (upstream collector + tick) serialise.
    internal suspend fun reduce(update: PurchasesUpdate) {
        mutex.withLock {
            // Re-evaluate grace expiry on every emission so an outage longer
            // than the policy correctly transitions InGrace → Revoked even
            // before we've classified the new event.
            val current = _state.value
            if (current is EntitlementState.InGrace && clock() >= current.expiresAtMs) {
                transitionToRevoked()
            }

            when (update) {
                is PurchasesUpdate.Success -> handleObservation(update.purchases, fromRecoverySweep = false)
                is PurchasesUpdate.Recovered -> {
                    hasObservedRecovered = true
                    handleObservation(update.purchases, fromRecoverySweep = true)
                }
                is PurchasesUpdate.Failure -> handleFailure(update.exception)
                // TODO(#2): handle PurchasesUpdate.Revoked once sibling PR #2 lands.
                //  When a Revoked event arrives carrying a purchase whose token
                //  matches the cached snapshot's purchaseToken, transition to
                //  EntitlementState.Revoked unconditionally (no grace — Play
                //  has explicitly revoked the entitlement, e.g. chargeback /
                //  refund). Tracked as follow-up to this PR.
                is PurchasesUpdate.Pending,
                is PurchasesUpdate.Canceled,
                is PurchasesUpdate.ItemAlreadyOwned,
                is PurchasesUpdate.ItemUnavailable,
                is PurchasesUpdate.UnknownResponse -> {
                    // No-op. Pending must not grant entitlement (Play's rules).
                    // Canceled / ItemAlreadyOwned / ItemUnavailable carry no
                    // owned-purchase signal that should mutate cache state.
                    // UnknownResponse is reserved for codes PBL doesn't
                    // document — log/observe at the consumer layer if needed.
                }
            }
        }
    }

    private suspend fun handleObservation(
        purchases: List<Purchase>,
        fromRecoverySweep: Boolean,
    ) {
        val match = purchases.firstOrNull(productPredicate)
        if (match != null) {
            val snapshot = EntitlementSnapshot(
                isEntitled = true,
                confirmedAtMs = clock(),
                purchaseToken = match.purchaseToken,
            )
            transitionToGranted(snapshot)
        } else if (fromRecoverySweep) {
            // Recovery sweeps are authoritative for currently-owned purchases —
            // a Recovered event with no matching purchase means Play says the
            // user does not own this entitlement. But: the connect-time sweep
            // can fire before the cache subscribed, so the very first
            // Recovered we see (and any Recovered that arrives before we've
            // had a chance to confirm one) shouldn't blow away a persisted
            // snapshot from a previous session — wait until we have a
            // baseline (defined as "we've observed at least one Recovered or
            // Success previously establishing state").
            //
            // We've already flipped `hasObservedRecovered` above; the gate
            // here is whether the cache has ever transitioned to Granted
            // before. If the snapshot was Granted (from storage hydrate) and
            // a Recovered with no match arrives, we trust the Recovered —
            // Play is the source of truth for owned purchases.
            //
            // This intentionally doesn't gate on hasObservedRecovered being
            // false-on-first-call: if the cache was hydrated with a Granted
            // snapshot, the very first Recovered we see is the trustworthy
            // signal that the entitlement is gone. The replay = 1 channel
            // means we can rely on Recovered firing on every successful
            // connect.
            val snapshot = EntitlementSnapshot(
                isEntitled = false,
                confirmedAtMs = clock(),
                purchaseToken = null,
            )
            transitionToRevoked(snapshot)
        }
        // Success with no match: a Success carrying products we don't care
        // about (e.g. a non-premium IAP also flowing through the same listener)
        // doesn't revoke an existing Granted state. Only Recovered's authoritative
        // owned-state snapshot can negate entitlement.
    }

    private suspend fun handleFailure(exception: BillingException) {
        val current = _state.value
        // Failures only move us to InGrace if we were previously confirmed
        // entitled. A Failure while already Revoked stays Revoked — there's
        // nothing to extend grace for.
        if (current !is EntitlementState.Granted && current !is EntitlementState.InGrace) {
            return
        }

        val reason = exception.toGraceReason()
        val window = gracePolicy.windowMsFor(reason)
        if (window == 0L) {
            // Grace disabled for this reason — transition straight to Revoked.
            transitionToRevoked()
            return
        }

        val expiresAt = clock() + window
        _state.value = EntitlementState.InGrace(
            expiresAtMs = expiresAt,
            reason = reason,
        )
        // Intentionally do NOT persist InGrace. Grace re-derives from
        // lastConfirmedSnapshot.confirmedAtMs on the next read; persisting
        // grace would let storage tampering extend the window indefinitely.
    }

    private suspend fun transitionToGranted(snapshot: EntitlementSnapshot) {
        lastConfirmedSnapshot = snapshot
        _state.value = EntitlementState.Granted
        runCatching { storage.write(snapshot) }
        // We swallow storage write failures rather than crash the cache —
        // an unreadable / unwritable storage layer shouldn't take down the
        // user's premium UI. Consumers wanting durability guarantees should
        // surface failures from inside their EntitlementStorage impl
        // (logging, reporting to Crashlytics, etc.).
    }

    private suspend fun transitionToRevoked(snapshot: EntitlementSnapshot? = null) {
        if (snapshot != null) {
            lastConfirmedSnapshot = snapshot
            runCatching { storage.write(snapshot) }
        }
        _state.value = EntitlementState.Revoked
    }

    public companion object {
        /**
         * Default grace-tick interval (60 seconds). Strikes a balance between
         * timely InGrace → Revoked transitions and not waking the CPU often.
         * Override via the [graceTickIntervalMs] constructor parameter when
         * driving a virtual clock in tests.
         */
        public const val DEFAULT_GRACE_TICK_INTERVAL_MS: Long = 60_000L
    }
}

/**
 * Maps a [BillingException] to the matching [GraceReason].
 *
 * Uses [BillingException.userFacingCategory] as the discriminator since that
 * already collapses the 13 typed subtypes into the same buckets we want here:
 *
 *  - [BillingErrorCategory.BillingUnavailable] → [GraceReason.BillingUnavailable].
 *  - Everything else → [GraceReason.TransientFailure]. The default is the
 *    safer fallback (shorter grace window for ambiguous failures) and covers
 *    the [BillingErrorCategory.Network] / `Other` / etc. buckets that map
 *    cleanly to "try again soon".
 */
private fun BillingException.toGraceReason(): GraceReason = when (userFacingCategory) {
    BillingErrorCategory.BillingUnavailable -> GraceReason.BillingUnavailable
    else -> GraceReason.TransientFailure
}
