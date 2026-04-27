package com.kanetik.billing.ext

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner

/**
 * Returns true if [activity] is in a state where launching a Play Billing flow is safe.
 *
 * Specifically, the activity must:
 *  - not be finishing or destroyed, and
 *  - if it's a [LifecycleOwner], be at least in the [Lifecycle.State.STARTED] state.
 *
 * Calling [com.kanetik.billing.BillingActions.launchFlow] against a finishing /
 * destroyed / not-yet-started activity is one of the most common sources of crashes
 * in Play Billing integrations (the underlying `ProxyBillingActivity` can NPE on a
 * stale window). This guard is cheap and prevents the foot-gun.
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
        if (!state.isAtLeast(Lifecycle.State.STARTED)) return false
    }
    return true
}
