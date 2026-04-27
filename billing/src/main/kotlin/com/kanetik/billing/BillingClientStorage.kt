package com.kanetik.billing

import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.kanetik.billing.factory.BillingConnectionFactory
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.shareIn

internal class BillingClientStorage(
    billingFactory: BillingConnectionFactory,
    logger: BillingLogger = BillingLogger.Noop,
    connectionShareScope: CoroutineScope = ProcessLifecycleOwner.get().lifecycleScope
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
     * after periods of genuine inactivity (less “greedy” than always-on eager collection).
     *
     * Approach: SharingStarted.WhileSubscribed with a 30s stopTimeoutMillis grace window.
     * - First subscriber starts the upstream (establishes billing connection).
     * - After the last subscriber disappears, we keep the connection alive for up to 30 seconds.
     *   If a new subscriber arrives inside that window we avoid a disconnect/reconnect cycle.
     * - After 30s of zero subscribers, the upstream is cancelled, allowing a clean disconnect.
     *
     * replay = 1 is retained so newcomers during an active period (or within the grace window)
     * immediately get the latest emission (e.g., connection state / cached info) without forcing
     * a new start.
     *
     * Monitoring / follow-up:
     * - Track Crashlytics for recurrence of the stopwatch IllegalStateException. If it returns,
     *   consider increasing stopTimeoutMillis (e.g. 60_000) or reverting to Eagerly.
     * - If battery/network usage appears unnecessarily high, consider reducing the timeout or
     *   instrumenting actual idle gap distribution to tune precisely.
     * - If usage becomes extremely sparse (minutes between operations) and churn is not causing
     *   issues, a shorter timeout (5–10s) might be acceptable.
     *
     * Adjustment guide:
     *   More stability (fewer reconnects)  -> increase stopTimeoutMillis or use Eagerly
     *   More resource conservation          -> decrease stopTimeoutMillis (watch for churn/crash)
     */
    val connectionFlow = billingFactory
        .createBillingConnectionFlow(FlowPurchasesUpdatedListener(_purchasesUpdateFlow, logger))
        .shareIn(
            scope = connectionShareScope,
            replay = 1,
            started = SharingStarted.WhileSubscribed(
                stopTimeoutMillis = 60_000 // 60s grace to smooth transient gaps
            )
        )
}
