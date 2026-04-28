package com.kanetik.billing.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.kanetik.billing.BillingConnector
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

/**
 * Keeps the Play Billing connection warm while a [LifecycleOwner] is started.
 *
 * Attach to an [androidx.activity.ComponentActivity] (or any LifecycleOwner) when
 * your screen needs to query products / launch flows / observe updates without
 * paying connection-startup latency on every action:
 *
 * ```
 * class UpgradeActivity : ComponentActivity() {
 *     @Inject lateinit var billing: BillingRepository
 *
 *     override fun onCreate(state: Bundle?) {
 *         super.onCreate(state)
 *         lifecycle.addObserver(BillingConnectionLifecycleManager(billing))
 *     }
 * }
 * ```
 *
 * `onStart` collects [BillingConnector.connectToBilling] (no-op consumer; the act
 * of collecting holds the shared connection upstream alive). `onStop` cancels the
 * collection, allowing the upstream's `WhileSubscribed` grace window to release the
 * underlying `BillingClient` after the configured timeout.
 *
 * @param connectable Typically the [BillingRepository][com.kanetik.billing.BillingRepository]
 *   itself or any narrower [BillingConnector] view of it.
 */
public class BillingConnectionLifecycleManager(
    private val connectable: BillingConnector
) : DefaultLifecycleObserver, CoroutineScope {

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext
        get() = job + Dispatchers.Main + CoroutineExceptionHandler { _, _ ->
            // Connection-level exceptions surface to consumers via
            // BillingConnectionResult.Error on collection of connectToBilling().
            // The lifecycle-collector intentionally swallows them here so a
            // transient connection error doesn't crash the host activity.
        }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        launch {
            connectable.connectToBilling().collect {
                // No-op — collection itself is the side effect that keeps the
                // shared connection upstream alive.
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        coroutineContext.cancelChildren()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        super.onDestroy(owner)
        // Cancel the SupervisorJob itself (cancelChildren in onStop only kills
        // active coroutines, leaving the parent job — and its captured exception
        // handler closure — alive until the host's own GC). On activity destroy,
        // the manager is permanently done; release the job too.
        job.cancel()
    }
}
