package com.kanetik.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.QueryPurchasesParams
import com.android.billingclient.api.queryPurchasesAsync
import com.kanetik.billing.factory.BillingConnectionFactory
import com.kanetik.billing.logging.BillingLogger
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
     * Buffer capacity is intentionally generous. Purchase updates are too important to
     * drop — the 1-slot buffer that was here previously could silently reject a second
     * purchase event if a collector was temporarily slow (e.g. a coroutine suspended
     * mid-acknowledge). With client-side signature verification in place, a dropped
     * valid purchase would look like "no purchase found" and deny a legitimate user
     * their premium grant. 32 slots is overkill for a sane flow (a user can only tap
     * "Buy" so fast) and gives plenty of headroom for slow collectors. If a drop ever
     * happens, [FlowPurchasesUpdatedListener] logs it for alerting.
     */
    private val _purchasesUpdateFlow = MutableSharedFlow<PurchasesUpdate>(extraBufferCapacity = 32)
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
            val unacknowledged = coroutineScope {
                val inApp = async { queryUnacknowledged(client, BillingClient.ProductType.INAPP) }
                val subs = async { queryUnacknowledged(client, BillingClient.ProductType.SUBS) }
                inApp.await() + subs.await()
            }
            if (unacknowledged.isEmpty()) return

            logger.d("Recovery sweep found ${unacknowledged.size} unacknowledged purchase(s)")
            val emitted = _purchasesUpdateFlow.tryEmit(PurchasesUpdate.Recovered(unacknowledged))
            if (!emitted) {
                val productIds = unacknowledged.flatMap { it.products }.distinct()
                logger.e(
                    "Recovered purchase update dropped — buffer exhausted. " +
                        "purchaseCount=${unacknowledged.size} productIds=$productIds"
                )
            }
        } catch (t: Throwable) {
            // Best-effort: log and bail. Next connect retries.
            logger.w("Purchase recovery sweep failed", t)
        }
    }

    private suspend fun queryUnacknowledged(
        client: BillingClient,
        @BillingClient.ProductType productType: String
    ): List<Purchase> {
        val params = QueryPurchasesParams.newBuilder().setProductType(productType).build()
        return client.queryPurchasesAsync(params).purchasesList
            .filter { it.purchaseState == Purchase.PurchaseState.PURCHASED && !it.isAcknowledged }
    }
}
