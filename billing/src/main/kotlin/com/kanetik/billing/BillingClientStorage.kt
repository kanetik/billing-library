package com.kanetik.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryPurchasesAsync
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.factory.BillingConnectionFactory
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterNot
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

internal class BillingClientStorage(
    billingFactory: BillingConnectionFactory,
    private val logger: BillingLogger,
    connectionShareScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val recoverPurchasesOnConnect: Boolean = true
) {
    /*
     * Three-channel architecture
     * --------------------------
     * Live PBL events, recovery-sweep events, and external revocation events
     * have different replay requirements:
     *
     *  - Live events (purchase flow Live/Canceled/etc., listener-driven)
     *    must NOT replay on re-subscription. A `repeatOnLifecycle` collector
     *    that re-attaches after a configuration change should not see the
     *    last `Live` event again — re-running entitlement grants and
     *    one-shot UX (confetti, toasts, analytics) on every rotation is the
     *    classic SharedFlow-replay footgun.
     *
     *  - Recovery events (auto-sweep on connect) MUST replay to a late
     *    subscriber. The sweep can fire before the consumer's collector
     *    attaches in some patterns; without replay the recovery is lost
     *    and Play auto-refunds the unacknowledged purchase ~3 days later.
     *
     *  - Revocation events (external `emitExternalRevocation` calls driven
     *    by the consumer's RTDN→Pub/Sub→FCM pipeline) MUST replay to a late
     *    subscriber. Revocations frequently arrive on a background FCM
     *    listener before the UI's collector attaches; without replay the
     *    revocation is silently dropped and entitlement stays granted.
     *
     * Earlier revisions used a single MutableSharedFlow with replay = 1,
     * which forced consumers to dedupe live events to avoid the re-fire
     * bug. The split here keeps each channel's replay semantics correct
     * without imposing dedupe duty on every consumer. Revocation gets its
     * own channel rather than sharing the recovery channel because the
     * recovery channel is typed narrower as [OwnedPurchases.Recovered] —
     * which doesn't accept [PurchaseRevoked] — and conceptually a revocation
     * is a third, distinct category (external signal, not owned-state and
     * not flow attempt outcome).
     *
     * Public exposure: [purchasesUpdateFlow] merges all three channels into
     * one [Flow]. Late subscribers see the most recent recovery sweep plus up
     * to the last 16 cached revocations (the revocation channel is sized for
     * the realistic FCM-burst case — see [_revocationUpdates]) plus all
     * future emissions from all three channels. Live events flow through with
     * no replay.
     */

    /** Live PBL events from the purchases-updated listener. No replay. */
    private val _liveUpdates = MutableSharedFlow<PurchaseEvent>(replay = 0, extraBufferCapacity = 32)

    /**
     * Tokens already acknowledged / consumed via [BillingActions.handlePurchase]
     * (or the lower-level [BillingActions.acknowledgePurchase] /
     * [BillingActions.consumePurchase]) during the lifetime of this
     * [BillingClientStorage] instance. The Recovered branch of
     * [purchasesUpdateFlow] reads this set synchronously inside its `map`
     * transform (one snapshot per delivery), so the dedupe applies both to a
     * fresh sweep result that races a concurrent ack landing AND to the
     * replay-1 cache delivered to a late subscriber after the consumer has
     * already handled the snapshot. Note: updates to this set do *not*
     * trigger re-emission to existing subscribers — they were the ones who
     * acked the purchase, so they already know — and the next sweep emission
     * (or a late subscriber's first read) is when the new state takes effect
     * downstream.
     *
     * Lifetime: tied to this [BillingClientStorage] instance — typically the
     * lifetime of whatever holds the singleton [BillingRepository] (process
     * lifetime for app-level DI, ViewModel lifetime for ViewModel-scoped
     * setups). [SharingStarted.WhileSubscribed] only restarts the upstream
     * connection flow when subscribers come and go; it does NOT recreate this
     * `BillingClientStorage` instance, so the set survives connection
     * teardowns. Growth is bounded by purchase activity (~50 bytes per token);
     * a typical user accumulates a handful of entries per session, but a
     * very long-lived process (months without a kill) on a power user with
     * many distinct purchases could accumulate hundreds — small in absolute
     * terms but technically unbounded. If real-world usage ever surfaces
     * pressure, the right fix is a public clear/reset API or a periodic
     * eviction tied to disconnect.
     */
    private val acknowledgedTokens = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Records [token] as acknowledged / consumed. Updates [acknowledgedTokens]
     * synchronously; the next time [purchasesUpdateFlow] delivers from the
     * recovery cache to any subscriber (a fresh sweep emission, or a late
     * subscriber attaching), the in-flight `map` reads the new set value and
     * filters the token out. Existing subscribers won't be re-notified about
     * the now-acked snapshot — they were the ones who handled it, so they
     * already know.
     */
    internal fun markAcknowledged(token: String) {
        acknowledgedTokens.update { it + token }
    }

    /**
     * Recovery-sweep events. Typed narrower as [OwnedPurchases.Recovered] —
     * the only thing emitted on this channel is the sweep result. Replay = 1
     * so a late subscriber gets a fresh sweep snapshot to filter against
     * [acknowledgedTokens] in the downstream `map`. The sweep always emits its
     * raw result here (no upstream dedupe); filtering happens downstream in
     * [purchasesUpdateFlow]
     * so the cache always reflects current Play state.
     */
    private val _recoveredUpdates = MutableSharedFlow<OwnedPurchases.Recovered>(replay = 1, extraBufferCapacity = 4)

    /**
     * External revocation events (consumer-driven via
     * [emitExternalRevocation]). Typed narrower as [PurchaseRevoked] for the
     * same reasons as [_recoveredUpdates]. Replay = 16 so a *small burst* of
     * revocations arriving before any subscriber attaches (e.g. multiple FCM
     * messages decoded at process start, before the UI is up) survives the
     * gap without collapsing — replay = 1 would only retain the most recent
     * revocation, silently dropping earlier ones for distinct purchase
     * tokens. 16 is a generous bound for the realistic FCM-burst case
     * (~200 bytes per cached event = ~3 KB worst case); larger bursts still
     * cap at 16, so consumers needing guaranteed delivery of every event
     * persist on their side before calling [emitExternalRevocation].
     */
    private val _revocationUpdates = MutableSharedFlow<PurchaseRevoked>(replay = 16, extraBufferCapacity = 16)

    /**
     * Public-facing merged stream of [PurchaseEvent]s. Hot, shared via the
     * underlying [SharedFlow]s; each subscription to this Flow subscribes to
     * all three channels. Returns [Flow] (not [SharedFlow]) because the type
     * can't express "replay-on-subscribe for some emissions but not others" —
     * that's exactly what the channel split provides, and exposing a SharedFlow
     * at the top would re-introduce the single-replay-slot problem the split
     * solves.
     *
     * The Recovered branch is `map`ped through a synchronous read of
     * [acknowledgedTokens], so the dedupe applies *at delivery time* rather
     * than at sweep-emission time. That's the difference that closes the
     * late-subscriber footgun: even if the consumer acks a purchase between
     * the sweep emission and a late subscriber attaching, the late subscriber
     * receives the cached sweep result re-filtered against the current
     * acked-token set — not the stale pre-ack snapshot. Empty Recovered
     * (intrinsic or filtered-to-empty) is dropped via `filterNot`.
     *
     * Why `map { acknowledgedTokens.value }` and not `combine(acknowledgedTokens)`:
     * `combine` would re-emit on every [acknowledgedTokens] update, requiring
     * `distinctUntilChanged` to suppress redundant emissions — and that
     * `distinctUntilChanged` would also suppress sweep N+1 with the same
     * content as sweep N, killing the retry signal that consumers rely on
     * when their handler fails on a recovered purchase. The `map`-with-value
     * approach only re-emits when [_recoveredUpdates] itself emits (a fresh
     * sweep), so consecutive identical sweep results pass through (retry
     * signal preserved) while late subscribers still get the dynamic filter
     * (their first delivery applies the current acked set).
     *
     * Live events and revocations bypass the filter — live events aren't
     * replayed at all, and [PurchaseRevoked] is a per-event external signal
     * that has nothing to do with the acked-token set (each carries its own
     * `purchaseToken`; the consumer's handler uses that, not the library's
     * ack tracker).
     */
    val purchasesUpdateFlow: Flow<PurchaseEvent> = merge(
        _liveUpdates,
        _recoveredUpdates
            .map { recovered ->
                // Snapshot acknowledgedTokens.value once per delivery so the
                // filter can't see a partial update if markAcknowledged runs
                // concurrently mid-iteration. (StateFlow.value reads are
                // atomic, so the snapshot is itself safe to read; the local
                // just bounds the filter to a single consistent set.)
                val acked = acknowledgedTokens.value
                OwnedPurchases.Recovered(recovered.purchases.filterNot { it.purchaseToken in acked })
            }
            .filterNot { it.purchases.isEmpty() },
        _revocationUpdates
    )

    /*
     * Billing connection sharing strategy
     * -----------------------------------
     * Problem observed: Crashlytics reported IllegalStateException("This stopwatch is already running.")
     * within Play Billing internals, consistent with rapid startConnection()/endConnection() churn
     * triggering overlapping or closely sequenced connection attempts.
     *
     * Goal: Reduce rapid connect/disconnect cycles while still allowing the connection to release
     * after periods of genuine inactivity (less "greedy" than always-on eager collection).
     *
     * Approach: SharingStarted.WhileSubscribed with a 60s stopTimeoutMillis grace window.
     * - First subscriber starts the upstream (establishes billing connection).
     * - After the last subscriber disappears, we keep the connection alive for up to 60 seconds.
     *   If a new subscriber arrives inside that window we avoid a disconnect/reconnect cycle.
     * - After 60s of zero subscribers, the upstream is cancelled, allowing a clean disconnect.
     *
     * replay = 1 is retained so newcomers during an active period (or within the grace window)
     * immediately get the latest emission (e.g., connection state / cached info) without forcing
     * a new start.
     */
    private val sharingStrategy = SharingStarted.WhileSubscribed(stopTimeoutMillis = 60_000)

    /**
     * Internal: live-client-bearing flow used by [DefaultBillingRepository] to obtain the
     * underlying [com.android.billingclient.api.BillingClient] for in-library calls.
     *
     * The `transformLatest` block forwards each connection state downstream and, on
     * `Connected`, runs the recovery sweep. `transformLatest` (not `onEach` + `launch`)
     * is what binds the sweep's lifecycle to the upstream collection: when [shareIn]
     * cancels via `WhileSubscribed`, an in-flight sweep is cancelled too. It also
     * cancels overlapping sweeps from rapid reconnects — a new state event cancels
     * the previous transform block and starts a new one.
     *
     * The sweep runs under [ioDispatcher] so its filtering / concat work doesn't
     * occupy the Main thread (the connection share scope defaults to
     * `ProcessLifecycleOwner.lifecycleScope`, which is Main-bound).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val connectionFlow: SharedFlow<InternalConnectionState> = billingFactory
        .createBillingConnectionFlow(FlowPurchasesUpdatedListener(_liveUpdates, logger))
        .transformLatest { state ->
            emit(state)
            if (recoverPurchasesOnConnect && state is InternalConnectionState.Connected) {
                withContext(ioDispatcher) {
                    sweepUnacknowledgedPurchases(state.client)
                }
            }
        }
        .shareIn(connectionShareScope, replay = 1, started = sharingStrategy)

    /**
     * Public-facing connection state for [BillingConnector.connectToBilling]. Mapped from
     * [connectionFlow] so the live [com.android.billingclient.api.BillingClient] doesn't
     * leak into the consumer-facing API. Re-shared so it carries proper SharedFlow
     * semantics (replay/buffering) independent of [connectionFlow]'s upstream.
     */
    val connectionResultFlow: SharedFlow<BillingConnectionResult> = connectionFlow
        .map { state ->
            when (state) {
                is InternalConnectionState.Connected -> BillingConnectionResult.Success
                is InternalConnectionState.Failed -> BillingConnectionResult.Error(state.exception)
            }
        }
        .shareIn(connectionShareScope, replay = 1, started = sharingStrategy)

    /**
     * Pushes a [PurchaseRevoked] event through the dedicated revocation
     * channel (`replay = 16`) so late subscribers catch up to the last
     * 16 cached revocations. Used by
     * [DefaultBillingRepository.emitExternalRevocation] to route
     * consumer-supplied revocation signals through the public
     * [purchasesUpdateFlow]. Suspending `emit` rather than `tryEmit` so a
     * transient buffer-full doesn't silently drop the event.
     */
    suspend fun emitExternalRevocation(purchaseToken: String, reason: RevocationReason) {
        _revocationUpdates.emit(PurchaseRevoked(purchaseToken, reason))
    }

    /**
     * Queries owned `INAPP` (and, if supported on this Play install, `SUBS`)
     * purchases in parallel, filters for `PURCHASED && !isAcknowledged`, and
     * emits any matches through [_recoveredUpdates] as a
     * [OwnedPurchases.Recovered] event.
     *
     * `SUBS` is gated on [BillingClient.isFeatureSupported] so apps running on
     * Play installs / regions / devices without subscription support don't log
     * a `FEATURE_NOT_SUPPORTED` warning on every connect — common enough to be
     * noise rather than signal.
     *
     * Failures are logged and swallowed — the sweep is best-effort.
     * `CancellationException` is rethrown explicitly so structured cancellation
     * (parent scope tearing down, [SharingStarted.WhileSubscribed] grace
     * expiring, transformLatest cancelling on next state) propagates correctly
     * — `runCatching` / `catch (Throwable)` would swallow it.
     *
     * Idempotency: once the consumer's collector acknowledges / consumes a
     * purchase, the token is recorded via [markAcknowledged] and filtered
     * out of subsequent emissions for the lifetime of this storage instance.
     * Play also eventually marks `isAcknowledged = true` (or removes the
     * consumed purchase) so re-queries naturally stop returning it; the
     * internal tracker covers the propagation gap and the replay-1
     * re-emission case.
     */
    private suspend fun sweepUnacknowledgedPurchases(client: BillingClient) {
        // v0.1.x limitation: SUBS purchases are emitted through the same
        // OwnedPurchases.Recovered variant as one-time products, even when
        // they're subscription replacements (carry a non-empty
        // linkedPurchaseToken in originalJson). Consumers handling subs must
        // parse linkedPurchaseToken themselves and treat replacement purchases
        // as plan changes rather than fresh grants — see OwnedPurchases.Recovered
        // KDoc and the README "Purchase recovery" section. v0.2.0 will ship a
        // typed OwnedPurchases.SubscriptionReplacement variant that classifies
        // these at the source so the wrong handling can't compile.
        try {
            val (inApp, subs) = coroutineScope {
                val inAppDeferred = async { queryUnacknowledgedSafely(client, BillingClient.ProductType.INAPP) }
                val subsDeferred = async {
                    if (subscriptionsSupported(client)) {
                        queryUnacknowledgedSafely(client, BillingClient.ProductType.SUBS)
                    } else {
                        // Genuinely unsupported (FEATURE_NOT_SUPPORTED): treat as
                        // "no SUBS purchases" rather than a failure. The combined
                        // sweep is still complete from the consumer's perspective.
                        Result.success(emptyList())
                    }
                }
                inAppDeferred.await() to subsDeferred.await()
            }

            // Partial-failure handling — final design. Three viable strategies
            // for what to emit when one of (INAPP, SUBS) succeeds and the
            // other fails were considered, all bounded by retry:
            //
            //   (a) skip emit (THIS IMPLEMENTATION): the previous Recovered
            //       emission stays in the replay slot, so late subscribers
            //       see last-known-valid state from the previous *successful*
            //       sweep. Fresh recoveries on the succeeded side are
            //       temporally stranded until the next clean sweep.
            //   (b) emit fresh side, clear failed side: faster fresh
            //       exposure, but a transient INAPP/SUBS failure can
            //       overwrite the last known unacknowledged purchase with
            //       Recovered([]) (or a partial list), so a late subscriber
            //       sees an empty replay even when Play still has pending
            //       purchases on the failed side.
            //   (c) emit fresh side, preserve stale cache for failed side:
            //       fastest exposure, but replays stale snapshots whose
            //       Purchase.isAcknowledged is `false` even after the
            //       consumer acked them — re-handle calls then surface
            //       Failure(DeveloperErrorException) for non-consumables
            //       and Failure(ItemNotOwnedException) for consumables.
            //
            // (a) is final. The only option where the library never emits
            // misleading state — replay always reflects a fully-successful
            // prior sweep. The internal acknowledged-token tracker now
            // handles cross-sweep stale-snapshot dedupe, so consumers don't
            // need a `Set<String>` for that case either. (b) loses
            // data; (c) emits stale-as-fresh. (a) just delays — bounded by
            // retry, with the previous Recovered preserved as a defensive
            // floor.
            //
            // This decision has been re-litigated multiple times in review;
            // the trade-off is documented here so future changes start from
            // an explicit baseline rather than re-deriving from first
            // principles. If a real consumer reports the (a) "stranded
            // until next clean sweep" delay as a problem in production,
            // revisit with concrete numbers.
            if (inApp.isFailure || subs.isFailure) {
                logger.w(
                    "Recovery sweep skipped emit due to partial query failure " +
                        "(inApp=${inApp.isFailure}, subs=${subs.isFailure}) — " +
                        "previous Recovered preserved; next connect retries"
                )
                return
            }

            val unacknowledged = inApp.getOrThrow() + subs.getOrThrow()
            // Always emit the raw sweep result so the replay-1 cache reflects
            // current Play state. Filtering against acknowledgedTokens happens
            // downstream inside purchasesUpdateFlow's `map` (snapshotting the
            // acked set per delivery), so a late subscriber sees the cached
            // sweep result re-filtered against the current acked set — not
            // whatever was visible at sweep time. Doing the filter at
            // delivery time (rather than emission time) is what closes the
            // late-subscriber footgun: even if the consumer acks a purchase
            // between this emit and a late subscriber attaching, the late
            // subscriber's first read goes through the map and gets the
            // up-to-date filtered Recovered.
            //
            // Suspending emit (not tryEmit) so a transient buffer-full
            // doesn't silently drop a recovery event.
            logger.d("Recovery sweep result: ${unacknowledged.size} purchase(s) (raw, pre-filter)")
            _recoveredUpdates.emit(OwnedPurchases.Recovered(unacknowledged))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Best-effort: log and bail. Next connect retries.
            logger.w("Purchase recovery sweep failed", e)
        }
    }

    /**
     * Returns `true` if subscription purchases should be queried on this
     * connect, `false` only if the Play Billing client reports
     * `FEATURE_NOT_SUPPORTED`. Transient failures (`SERVICE_DISCONNECTED`,
     * `SERVICE_UNAVAILABLE`, etc.) fall through to `true` so the sweep still
     * attempts the SUBS query — those are recoverable states on a device
     * that does support subs, and silently skipping the SUBS recovery would
     * leak unacknowledged subscription purchases.
     */
    private fun subscriptionsSupported(client: BillingClient): Boolean {
        // Synchronous PBL call; one IPC round-trip per connect, well under
        // the cost of issuing a doomed queryPurchasesAsync(SUBS) plus
        // logging the warning each time.
        val responseCode = client.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS).responseCode
        return responseCode != BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED
    }

    /**
     * Returns `Result.success(purchases)` on a successful query, or
     * `Result.failure(throwable)` on any non-cancellation failure. Used to
     * distinguish "no purchases of this type" from "couldn't determine due
     * to a transient billing failure" — only the former should overwrite the
     * recovery replay cache.
     */
    private suspend fun queryUnacknowledgedSafely(
        client: BillingClient,
        @BillingClient.ProductType productType: String
    ): Result<List<Purchase>> = try {
        Result.success(queryUnacknowledged(client, productType))
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        logger.w("Recovery sweep: $productType query failed", e)
        Result.failure(e)
    }

    private suspend fun queryUnacknowledged(
        client: BillingClient,
        @BillingClient.ProductType productType: String
    ): List<Purchase> {
        val params = QueryPurchasesParams.newBuilder().setProductType(productType).build()
        val result = client.queryPurchasesAsync(params)
        if (result.billingResult.responseCode != BillingClient.BillingResponseCode.OK) {
            // Throw so the outer try/catch in queryUnacknowledgedSafely logs it.
            // Without this, a SERVICE_DISCONNECTED / ERROR for one product type
            // would silently return an empty list — recovery skipped, no signal.
            throw BillingException.fromResult(result.billingResult)
        }
        return result.purchasesList
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
    }
}
