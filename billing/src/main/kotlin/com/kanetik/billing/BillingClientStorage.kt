package com.kanetik.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryPurchasesAsync
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.factory.BillingConnectionFactory
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch

internal class BillingClientStorage(
    billingFactory: BillingConnectionFactory,
    private val logger: BillingLogger,
    private val connectionShareScope: CoroutineScope,
    private val recoverPurchasesOnConnect: Boolean = true
) {
    /*
     * Buffer + replay strategy
     * ------------------------
     * `replay = 1`: a late subscriber sees the most recent emission. This matters
     * for [PurchasesUpdate.Recovered] — the auto-sweep can fire on connect
     * before the consumer's `observePurchaseUpdates()` collector has had time
     * to attach (a real race when the lifecycle observer triggers `onStart`
     * before the launched collector runs). With replay=0, that emission would
     * be lost; with replay=1, the late subscriber picks it up.
     *
     * Cost — and consumer responsibility: the most recent emission is replayed
     * to *every* new subscriber, including subscribers that re-attach during
     * configuration changes (`repeatOnLifecycle`, ViewModel re-collect, etc.).
     * Handle/grant code is idempotent and absorbs this:
     * `acknowledgePurchase(Purchase)` short-circuits on `isAcknowledged`, and
     * consume on an already-consumed purchase surfaces `ITEM_NOT_OWNED`
     * (already typed and handled). **UI side effects are not idempotent**
     * — confetti, "thanks!" toasts, and analytics events will fire each
     * time a re-subscribed collector receives the replayed event. Consumers
     * that fire one-shot UX must dedupe by `purchaseToken` or correlate
     * against their own already-celebrated state. See [PurchasesUpdate]'s
     * class-level KDoc and the README's "Re-subscription replay" section
     * for the recommended pattern.
     *
     * The architectural alternative — splitting recovery state into a
     * `StateFlow<List<Purchase>>` and keeping live events on a replay=0
     * `SharedFlow` — eliminates the re-fire trade-off entirely but is a
     * bigger redesign; tracked in `docs/ROADMAP.md` for revisit if a real
     * consumer hits the re-fire bug.
     *
     * `extraBufferCapacity = 32`: head-room for slow collectors. The 1-slot
     * buffer that lived here previously could silently reject a second purchase
     * event if a collector was temporarily slow (e.g. a coroutine suspended
     * mid-acknowledge). 32 is overkill for a sane flow (a user can only tap
     * "Buy" so fast) and gives plenty of headroom. The sweep emits via the
     * suspending [MutableSharedFlow.emit] (not `tryEmit`) so that a hypothetical
     * buffer-full case suspends rather than drops.
     */
    private val _purchasesUpdateFlow = MutableSharedFlow<PurchasesUpdate>(replay = 1, extraBufferCapacity = 32)
    val purchasesUpdateFlow: SharedFlow<PurchasesUpdate> = _purchasesUpdateFlow
        .asSharedFlow()

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
     * The `onEach` hook fires the purchase-recovery sweep on every successful
     * connection. It runs *upstream* of [shareIn] so it only executes when there's
     * an active subscriber (preserving the [SharingStarted.WhileSubscribed] grace
     * window — adding a permanent collector here would defeat it). The sweep is
     * launched in [connectionShareScope] rather than awaited inline so a slow
     * `queryPurchasesAsync` round-trip can't delay downstream connection emissions.
     */
    val connectionFlow: SharedFlow<InternalConnectionState> = billingFactory
        .createBillingConnectionFlow(FlowPurchasesUpdatedListener(_purchasesUpdateFlow, logger))
        .onEach { state ->
            if (recoverPurchasesOnConnect && state is InternalConnectionState.Connected) {
                connectionShareScope.launch { sweepUnacknowledgedPurchases(state.client) }
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
     * Queries owned INAPP and SUBS purchases in parallel, filters for any that are
     * `PURCHASED && !isAcknowledged`, and emits them through [purchasesUpdateFlow]
     * as a [PurchasesUpdate.Recovered] event.
     *
     * Failures are logged and swallowed — the sweep is best-effort. If the
     * underlying query fails (network, service disconnect mid-call), the next
     * successful connection will trigger another attempt.
     *
     * Idempotency: once the consumer's collector acknowledges / consumes a
     * purchase, Play marks `isAcknowledged = true` and subsequent sweeps skip it.
     * In the narrow window between emit and ack landing, a second sweep can re-emit
     * the same purchase; the consumer's `acknowledgePurchase(Purchase)` overload's
     * `isAcknowledged` short-circuit absorbs the duplicate in the common case.
     */
    private suspend fun sweepUnacknowledgedPurchases(client: BillingClient) {
        try {
            // Each product-type query runs under its own try/catch so a failure for
            // one type (e.g. FEATURE_NOT_SUPPORTED on a device without SUBS, transient
            // SERVICE_DISCONNECTED during one of the two calls) doesn't cancel the
            // sibling and lose its results. CancellationException is explicitly
            // rethrown so structured cancellation (parent scope tearing down,
            // WhileSubscribed grace expiring) propagates correctly — runCatching /
            // catch(Throwable) would swallow it and leave the cancellation signal
            // lost. Per-type failures are logged so the signal doesn't get swallowed.
            val unacknowledged = coroutineScope {
                val inApp = async { queryUnacknowledgedSafely(client, BillingClient.ProductType.INAPP) }
                val subs = async { queryUnacknowledgedSafely(client, BillingClient.ProductType.SUBS) }
                inApp.await() + subs.await()
            }
            if (unacknowledged.isEmpty()) return

            logger.d("Recovery sweep found ${unacknowledged.size} unacknowledged purchase(s)")
            // Suspending emit (not tryEmit) so a transient buffer-full doesn't
            // silently drop a recovery event; the launch context can absorb the
            // brief suspend.
            _purchasesUpdateFlow.emit(PurchasesUpdate.Recovered(unacknowledged))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // Best-effort: log and bail. Next connect retries.
            logger.w("Purchase recovery sweep failed", e)
        }
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
            // Throw so the outer try/catch in sweepUnacknowledgedPurchases logs it.
            // Without this, a SERVICE_DISCONNECTED / ERROR for one product type
            // would silently return an empty list — recovery skipped, no signal.
            throw BillingException.fromResult(result.billingResult)
        }
        return result.purchasesList
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
    }
}
