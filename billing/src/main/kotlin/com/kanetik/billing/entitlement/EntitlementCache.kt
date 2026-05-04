package com.kanetik.billing.entitlement

import com.android.billingclient.api.Purchase
import com.kanetik.billing.FlowOutcome
import com.kanetik.billing.OwnedPurchases
import com.kanetik.billing.PurchaseEvent
import com.kanetik.billing.PurchaseRevoked
import com.kanetik.billing.exception.BillingErrorCategory
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import androidx.annotation.AnyThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
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
 * every consumer ends up reinventing on top of the raw `PurchaseEvent`
 * stream. Consumers that want a simple `StateFlow<EntitlementState>` they can
 * collect from a ViewModel get one; consumers that need the raw event stream
 * for custom logic continue to use `observePurchaseUpdates()` directly.
 *
 * ## What it does
 *
 *  - Hydrates from a consumer-provided [EntitlementStorage] so premium UI can
 *    render before the first network round-trip lands.
 *  - Treats [OwnedPurchases.Live] and [OwnedPurchases.Recovered] as
 *    grant-only signals: a match against `productPredicate` transitions to
 *    [EntitlementState.Granted]. A non-match does **not** revoke (Live can
 *    carry empty/UNSPECIFIED_STATE callbacks; Recovered emits only the
 *    unacked subset). Revocation flows through [PurchaseRevoked] and grace
 *    expiry — see "Sealed-when handling" below.
 *  - On [FlowOutcome.Failure], moves to [EntitlementState.InGrace] for a
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
 *     )
 *
 *     init {
 *         // start() is suspend so hydration completes before it returns —
 *         // the first read of cache.state.value reflects the persisted
 *         // snapshot, not the default Revoked. Launch it from viewModelScope
 *         // so init isn't blocked.
 *         viewModelScope.launch { cache.start(viewModelScope) }
 *     }
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
 * The cache reacts to four event paths:
 *  - [OwnedPurchases.Live] / [OwnedPurchases.Recovered]: **grant-only**.
 *    A match against [productPredicate] transitions to [EntitlementState.Granted]
 *    and persists the snapshot. A non-match on either does **not** revoke —
 *    `Live` can carry empty/UNSPECIFIED_STATE callbacks or unrelated
 *    products, and `Recovered` only emits the `PURCHASED && !isAcknowledged`
 *    subset filtered against the library's acknowledgedTokens set, so an
 *    already-acked entitling purchase will never appear there. Treating
 *    either as authoritative for revocation would falsely revoke users
 *    whose entitling purchase has already been acknowledged.
 *  - [FlowOutcome.Failure]: triggers [EntitlementState.InGrace] (or revokes
 *    immediately if the policy window is zero or has already elapsed since
 *    the last confirmation).
 *  - [com.kanetik.billing.PurchaseRevoked]: when `event.purchaseToken`
 *    matches the cached snapshot's `purchaseToken`, transitions to
 *    [EntitlementState.Revoked] immediately (no grace; Play has explicitly
 *    revoked). Consumers wire `emitExternalRevocation` against their
 *    RTDN→FCM pipeline — see the README "Server-driven revocation" section.
 *
 * The remaining [FlowOutcome] variants ([FlowOutcome.Pending],
 * [FlowOutcome.Canceled], [FlowOutcome.ItemAlreadyOwned],
 * [FlowOutcome.ItemUnavailable], [FlowOutcome.UnknownResponse]) are
 * intentionally no-ops here — they don't change owned-purchase state, and a
 * Pending purchase explicitly must not grant entitlement (per Play's rules).
 *
 * @param purchasesUpdates The hot purchase-update stream — typically
 *   [com.kanetik.billing.BillingPurchaseUpdatesOwner.observePurchaseUpdates].
 *   The cache subscribes inside [start].
 * @param storage Persistence layer. See [EntitlementStorage] for the
 *   contract.
 * @param gracePolicy How long to keep treating the user as entitled after a
 *   [FlowOutcome.Failure]. Pass [GracePolicy.None] to disable grace.
 * @param productPredicate Filter applied to each [Purchase] in an
 *   [OwnedPurchases.Live] / [OwnedPurchases.Recovered] event to decide
 *   whether it grants entitlement. Typical shapes:
 *   `{ it.products.contains("premium_lifetime") }` for a single SKU,
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
    private val purchasesUpdates: Flow<PurchaseEvent>,
    private val storage: EntitlementStorage,
    private val gracePolicy: GracePolicy,
    private val productPredicate: (Purchase) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
    private val graceTickIntervalMs: Long = DEFAULT_GRACE_TICK_INTERVAL_MS,
    private val logger: BillingLogger = BillingLogger.Noop,
) {
    init {
        require(graceTickIntervalMs > 0) {
            "graceTickIntervalMs must be > 0 (got $graceTickIntervalMs); zero or negative would " +
                "make the tick loop a hot loop, draining CPU/battery."
        }
    }

    private val _state = MutableStateFlow<EntitlementState>(EntitlementState.Revoked)

    /**
     * The current entitlement state. `Revoked` until [start] hydrates from
     * storage and the first event arrives.
     */
    public val state: StateFlow<EntitlementState> = _state.asStateFlow()

    // Most-recent confirmed snapshot. Tracked separately from `_state` so
    // grace transitions can preserve the underlying confirmed observation
    // (purchaseToken, confirmedAtMs) without re-querying storage, and so the
    // grace window can be anchored to confirmedAtMs instead of clock() (which
    // would let repeated Failures keep extending grace indefinitely).
    private var lastConfirmedSnapshot: EntitlementSnapshot? = null

    // Guards reduce() so concurrent emissions (the upstream flow + the
    // grace tick) can't race on _state / lastConfirmedSnapshot updates.
    private val mutex = Mutex()

    // Latched once start() begins, prevents redundant collectors if a caller
    // accidentally calls start() twice on the same instance. AtomicBoolean
    // (not just a Boolean) so the check is safe even if start() is called
    // from different threads.
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    // Completed when the first start() call has finished hydration. Second+
    // start() callers await this so they don't return before state.value
    // reflects the persisted snapshot (otherwise their immediate read would
    // see the default Revoked even after the racing first caller has
    // finished hydrating).
    private val hydrationComplete = CompletableDeferred<Unit>()

    /**
     * Hydrates from [storage] and begins collecting from [purchasesUpdates] +
     * ticking the grace clock inside [scope]. Suspends until hydration
     * completes, so by the time [start] returns, `state.value` already
     * reflects the persisted snapshot — callers that read `state` immediately
     * after `start()` returns will see the hydrated value, not the default
     * `Revoked`. A failed read is treated as "no prior snapshot" and logged
     * via [logger]; the cache proceeds with the default `Revoked` state.
     *
     * Returns the parent [Job] orchestrating the upstream collector and the
     * grace tick. Cancel the returned job (or the [scope] itself) to stop
     * the cache.
     *
     * Calling [start] more than once on the same instance is a no-op for
     * the second+ call: the latch returns the start-was-called signal and
     * the second [Job] completes immediately without launching a new
     * collector. Most apps create one cache per scope and start it once.
     */
    @AnyThread
    public suspend fun start(scope: CoroutineScope): Job {
        if (!started.compareAndSet(false, true)) {
            // Already started; second-call protection so a misbehaving caller
            // doesn't end up with N collectors all racing on the same upstream
            // flow + N tick coroutines all writing to storage. Wait for the
            // first call's hydration to complete before returning — otherwise
            // a thread-racing second caller could return early and read
            // state.value before the first call's hydration lands.
            hydrationComplete.await()
            return Job().apply { complete() }
        }

        // Hydrate from storage first so state is consistent before we begin
        // emitting transitions. Done here (in the suspend body) rather than
        // inside the launched coroutine so callers reading `state.value`
        // immediately after `start()` returns see the hydrated value, not
        // the default Revoked — preventing the brief UI flicker that would
        // otherwise happen on app start.
        try {
            val initial = try {
                storage.read()
            } catch (ce: CancellationException) {
                // Re-throw cancellation so structured concurrency works: a
                // cancelled scope shouldn't be turned into a swallowed
                // exception that logs but keeps the cache running.
                throw ce
            } catch (e: Throwable) {
                logger.e("EntitlementCache: failed to read snapshot from storage; proceeding with default Revoked state", e)
                null
            }
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
        } finally {
            // Always signal hydration done — even on CancellationException —
            // so a second caller blocked on hydrationComplete.await() unblocks.
            hydrationComplete.complete(Unit)
        }

        // Periodic tick to detect grace expiry without a fresh upstream event.
        // A consumer offline for 24h with the app foregrounded should still
        // see InGrace → Revoked the moment grace lapses, not the next time
        // Play sends an update.
        //
        // Tick lifecycle is bound to the upstream collect: if purchasesUpdates
        // ever completes normally, we cancel the tick so this start() Job can
        // finish too — without this, an infinite tick keeps the parent alive
        // forever even after the upstream is done.
        return scope.launch {
            val tickJob = launch { tickGraceWindow() }
            try {
                purchasesUpdates.collect { event ->
                    reduce(event)
                }
            } finally {
                tickJob.cancel()
            }
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
    internal suspend fun reduce(event: PurchaseEvent) {
        mutex.withLock {
            // Re-evaluate grace expiry on every emission so an outage longer
            // than the policy correctly transitions InGrace → Revoked even
            // before we've classified the new event.
            val current = _state.value
            if (current is EntitlementState.InGrace && clock() >= current.expiresAtMs) {
                transitionToRevoked()
            }

            when (event) {
                is OwnedPurchases.Live -> handleObservation(event.purchases)
                is OwnedPurchases.Recovered -> handleObservation(event.purchases)
                is FlowOutcome.Failure -> handleFailure(event.exception)
                is PurchaseRevoked -> handleRevoked(event)
                is FlowOutcome.Pending,
                is FlowOutcome.Canceled,
                is FlowOutcome.ItemAlreadyOwned,
                is FlowOutcome.ItemUnavailable,
                is FlowOutcome.UnknownResponse -> {
                    // No-op. Pending must not grant entitlement (Play's rules).
                    // Canceled / ItemAlreadyOwned / ItemUnavailable carry no
                    // owned-purchase signal that should mutate cache state.
                    // UnknownResponse is reserved for codes PBL doesn't
                    // document — log/observe at the consumer layer if needed.
                }
            }
        }
    }

    private suspend fun handleObservation(purchases: List<Purchase>) {
        val match = purchases.firstOrNull(productPredicate)
        if (match != null) {
            val snapshot = EntitlementSnapshot(
                isEntitled = true,
                confirmedAtMs = clock(),
                purchaseToken = match.purchaseToken,
            )
            transitionToGranted(snapshot)
        }
        // No-match cases (both Live and Recovered) intentionally do NOT
        // revoke. Live can carry empty/UNSPECIFIED_STATE callbacks or
        // products unrelated to this cache's productPredicate, and Recovered
        // only emits the PURCHASED && !isAcknowledged subset of owned
        // purchases (filtered against the library's acknowledgedTokens set
        // per the dedupe in BillingClientStorage). Treating either as
        // authoritative for revocation would falsely revoke users whose
        // entitling purchase has already been acknowledged: it never
        // appears in Recovered, so the predicate never matches, and an
        // empty Recovered would clobber a valid Granted state.
        //
        // Revocation in this cache flows through two narrow paths instead:
        //  - [PurchaseRevoked] — the consumer pushes an explicit
        //    revocation signal via emitExternalRevocation (typically
        //    decoded from RTDN→FCM payloads).
        //  - Grace-window expiry on persistent [FlowOutcome.Failure].
        // Recovered is grant-only here: it surfaces unacked purchases for
        // entitlement confirmation and does NOT speak to the revoke side.
    }

    private suspend fun handleRevoked(event: PurchaseRevoked) {
        // Match the revocation against the cached snapshot's purchaseToken.
        // If it matches, the purchase that established our entitlement has
        // been revoked Play-side — transition to Revoked unconditionally
        // (no grace; Play has explicitly revoked). If it doesn't match,
        // the revocation is for a different purchase the consumer is
        // tracking and this cache's state is unaffected.
        val confirmed = lastConfirmedSnapshot
        if (confirmed != null && confirmed.purchaseToken == event.purchaseToken) {
            val revokedSnapshot = EntitlementSnapshot(
                isEntitled = false,
                confirmedAtMs = clock(),
                purchaseToken = event.purchaseToken,
            )
            transitionToRevoked(revokedSnapshot)
        }
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

        // Anchor grace expiry to the last confirmed observation, not clock().
        // Using clock() would let repeated Failures keep extending the grace
        // window indefinitely — every new failure would push expiresAt
        // forward by `window`, defeating the bounded-grace guarantee. With
        // confirmedAtMs anchoring, multiple Failures arriving over time all
        // produce the same expiresAt (assuming reason is stable), and a
        // Failure long after the last confirmation correctly produces an
        // already-expired window that immediately transitions to Revoked.
        //
        // lastConfirmedSnapshot is non-null at this point: the early return
        // above only allows entry from Granted or InGrace, both of which
        // imply we've previously transitioned to Granted (which sets the
        // snapshot).
        val confirmedAt = lastConfirmedSnapshot?.confirmedAtMs ?: clock()
        val expiresAt = confirmedAt + window
        if (clock() >= expiresAt) {
            // Window has already elapsed since the last confirmation — skip
            // InGrace entirely and revoke now. Persisted via transitionToRevoked.
            transitionToRevoked()
            return
        }
        _state.value = EntitlementState.InGrace(
            expiresAtMs = expiresAt,
            reason = reason,
        )
        // Intentionally do NOT persist InGrace. The expiry derives from the
        // already-persisted confirmedAtMs + the policy window; persisting the
        // grace state itself would risk storage tampering reseting the
        // window.
    }

    private suspend fun transitionToGranted(snapshot: EntitlementSnapshot) {
        lastConfirmedSnapshot = snapshot
        _state.value = EntitlementState.Granted
        try {
            storage.write(snapshot)
        } catch (ce: CancellationException) {
            // Re-throw — structured concurrency. A cancelled scope shouldn't
            // be silently turned into a logged "Granted write failed".
            throw ce
        } catch (e: Throwable) {
            // Swallow rather than crash the cache — an unwritable storage
            // layer shouldn't take down the user's premium UI — but log so
            // integrators notice their EntitlementStorage impl is misbehaving.
            logger.e("EntitlementCache: failed to write Granted snapshot to storage", e)
        }
    }

    private suspend fun transitionToRevoked(snapshot: EntitlementSnapshot? = null) {
        // Always persist a Revoked snapshot — without this, grace-expiry paths
        // (tick + on-emission re-evaluation) would leave storage holding the
        // last Granted state, and the next process start would hydrate as
        // Granted incorrectly. If no fresh snapshot is supplied (typical for
        // grace-expiry transitions), build one from lastConfirmedSnapshot's
        // purchaseToken so the persisted record still ties back to the
        // purchase that just expired its grace.
        val toPersist = snapshot ?: EntitlementSnapshot(
            isEntitled = false,
            confirmedAtMs = clock(),
            purchaseToken = lastConfirmedSnapshot?.purchaseToken,
        )
        lastConfirmedSnapshot = toPersist
        // Set _state BEFORE persisting so the UI sees Revoked immediately,
        // even if the consumer's storage layer is slow (DataStore, signed
        // prefs against a remote key, etc.). The persist still happens; a
        // failure there is logged below. Persistence is for surviving
        // process restart; the live state.value drives the UI.
        _state.value = EntitlementState.Revoked
        try {
            storage.write(toPersist)
        } catch (ce: CancellationException) {
            // Re-throw — structured concurrency.
            throw ce
        } catch (e: Throwable) {
            // Failure to persist Revoked is more serious than failure to
            // persist Granted — the next process start could hydrate as
            // Granted from the stale snapshot and the user would briefly see
            // entitlement they no longer have. Log loudly; consumers wanting
            // durability guarantees should surface this from inside their
            // EntitlementStorage impl too.
            logger.e("EntitlementCache: failed to write Revoked snapshot to storage; next process start may hydrate as Granted from stale snapshot", e)
        }
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
