package com.kanetik.billing.ext

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Returns true if [activity] is in a state where launching a Play Billing flow is safe.
 *
 * Specifically, the activity must:
 *  - not be finishing or destroyed, and
 *  - if it's a [LifecycleOwner], be at least in the [Lifecycle.State.RESUMED] state.
 *
 * Calling [com.kanetik.billing.BillingActions.launchFlow] against a finishing /
 * destroyed / not-fully-resumed activity is one of the most common sources of
 * crashes in Play Billing integrations: the underlying `ProxyBillingActivity` can
 * NPE on a stale window, and on API 29+ Android's background-activity-start
 * restrictions can silently no-op the launch from a non-RESUMED activity.
 *
 * `RESUMED` (rather than `STARTED`) is the conservative gate — it's the only
 * lifecycle state that guarantees the activity's window is in the foreground and
 * interactive, which is what `launchBillingFlow` requires. The cost of being
 * conservative is near-zero (purchase clicks happen from RESUMED activities
 * essentially 100% of the time); the cost of being too permissive is silent
 * purchase failures on certain device/API combinations.
 *
 * Use it from the call-site that decides whether to start a purchase, e.g. an
 * `onClick` handler that has navigated through a coroutine pause:
 *
 * ```
 * if (!validatePurchaseActivity(activity)) {
 *     return  // user dismissed; nothing to do
 * }
 * billing.launchFlow(activity, productDetails.toOneTimeFlowParams())
 * ```
 */
public fun validatePurchaseActivity(activity: Activity): Boolean {
    if (activity.isFinishing || activity.isDestroyed) return false
    if (activity is LifecycleOwner) {
        val state = activity.lifecycle.currentState
        if (!state.isAtLeast(Lifecycle.State.RESUMED)) return false
    }
    return true
}
