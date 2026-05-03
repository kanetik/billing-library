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
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.withContext

internal class BillingClientStorage(
    billingFactory: BillingConnectionFactory,
    private val logger: BillingLogger,
    connectionShareScope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val recoverPurchasesOnConnect: Boolean = true
) {
    /*
     * Two-channel architecture
     * ------------------------
     * Live PBL events and recovery-sweep events have different replay
     * requirements:
     *
     *  - Live events (purchase flow Success/Canceled/etc., listener-driven)
     *    must NOT replay on re-subscription. A `repeatOnLifecycle` collector
     *    that re-attaches after a configuration change should not see the
     *    last `Success` event again — re-running entitlement grants and
     *    one-shot UX (confetti, toasts, analytics) on every rotation is the
     *    classic SharedFlow-replay footgun.
     *
     *  - Recovery events (auto-sweep on connect) MUST replay to a late
     *    subscriber. The sweep can fire before the consumer's collector
     *    attaches in some patterns; without replay the recovery is lost
     *    and Play auto-refunds the unacknowledged purchase ~3 days later.
     *
     * Earlier revisions used a single MutableSharedFlow with replay = 1,
     * which forced consumers to dedupe live events to avoid the re-fire
     * bug. The split here keeps each channel's replay semantics correct
     * without imposing dedupe duty on every consumer.
     *
     * Public exposure: [purchasesUpdateFlow] merges both channels into one
     * [Flow]. Late subscribers see the most recent recovery (if any) plus
     * all future emissions from both channels. Live events flow through
     * with no replay.
     */

    /** Live PBL events from the purchases-updated listener. No replay. */
    private val _liveUpdates = MutableSharedFlow<PurchasesUpdate>(replay = 0, extraBufferCapacity = 32)

    /** Recovery-sweep events. Replay = 1 so a late subscriber catches the most recent sweep. */
    private val _recoveredUpdates = MutableSharedFlow<PurchasesUpdate>(replay = 1, extraBufferCapacity = 4)

    /**
     * Public-facing merged stream of [PurchasesUpdate]s. Hot, shared via the
     * underlying [SharedFlow]s; each subscription to this Flow subscribes to
     * both channels. Returns [Flow] (not [SharedFlow]) because the type can't
     * express "replay-on-subscribe for some emissions but not others" — that's
     * exactly what the channel split provides, and exposing a SharedFlow at the
     * top would re-introduce the single-replay-slot problem the split solves.
     */
    val purchasesUpdateFlow: Flow<PurchasesUpdate> = merge(_liveUpdates, _recoveredUpdates)

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
     * Queries owned `INAPP` (and, if supported on this Play install, `SUBS`)
     * purchases in parallel, filters for `PURCHASED && !isAcknowledged`, and
     * emits any matches through [_recoveredUpdates] as a
     * [PurchasesUpdate.Recovered] event.
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
     * purchase, Play marks `isAcknowledged = true` (or removes the consumed
     * purchase) and subsequent sweeps skip it. A narrow window between emit
     * and ack landing exists, but `acknowledgePurchase(Purchase)`'s
     * `isAcknowledged` short-circuit absorbs the duplicate in the common case.
     */
    private suspend fun sweepUnacknowledgedPurchases(client: BillingClient) {
        try {
            val unacknowledged = coroutineScope {
                val inApp = async { queryUnacknowledgedSafely(client, BillingClient.ProductType.INAPP) }
                val subs = async {
                    if (subscriptionsSupported(client)) {
                        queryUnacknowledgedSafely(client, BillingClient.ProductType.SUBS)
                    } else {
                        emptyList()
                    }
                }
                inApp.await() + subs.await()
            }
            if (unacknowledged.isEmpty()) return

            logger.d("Recovery sweep found ${unacknowledged.size} unacknowledged purchase(s)")
            // Suspending emit (not tryEmit) so a transient buffer-full doesn't
            // silently drop a recovery event.
            _recoveredUpdates.emit(PurchasesUpdate.Recovered(unacknowledged))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Best-effort: log and bail. Next connect retries.
            logger.w("Purchase recovery sweep failed", e)
        }
    }

    private fun subscriptionsSupported(client: BillingClient): Boolean {
        // Synchronous PBL call; one IPC round-trip per connect, well under
        // the cost of issuing a doomed queryPurchasesAsync(SUBS) plus
        // logging the warning each time.
        return client.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
            .responseCode == BillingClient.BillingResponseCode.OK
    }

    private suspend fun queryUnacknowledgedSafely(
        client: BillingClient,
        @BillingClient.ProductType productType: String
    ): List<Purchase> = try {
        queryUnacknowledged(client, productType)
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Exception) {
        logger.w("Recovery sweep: $productType query failed", e)
        emptyList()
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
