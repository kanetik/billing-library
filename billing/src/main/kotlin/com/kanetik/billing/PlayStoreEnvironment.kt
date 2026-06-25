package com.kanetik.billing

import android.content.Context
import android.content.pm.PackageManager

/**
 * Internal: deterministic, offline check for whether the Google Play Store app
 * is present and enabled on this device. Backs the [BillingAvailability.UNAVAILABLE]
 * verdict of [BillingConnector.queryBillingAvailability].
 *
 * Abstracted behind a `fun interface` so [DefaultBillingRepository] stays
 * context-free and unit tests can supply a fake without a real
 * [android.content.pm.PackageManager].
 */
internal fun interface PlayStoreEnvironment {
    /**
     * `true` if the Play Store package is installed and enabled — i.e. Play
     * Billing has a chance of working. `false` only when the package is absent
     * or explicitly disabled, which is a stable, terminal fact about the device.
     */
    fun isPlayStoreUsable(): Boolean
}

/**
 * [PlayStoreEnvironment] backed by the real [PackageManager]. Checks the
 * Play Store package (`com.android.vending`) is installed and not disabled.
 *
 * Deliberately does **not** attempt to distinguish "billing supported for this
 * account/region" — that is not knowable offline and is intentionally left to
 * the connection attempt (which maps to [BillingAvailability.UNKNOWN] rather
 * than a false-terminal verdict).
 */
internal class DefaultPlayStoreEnvironment(context: Context) : PlayStoreEnvironment {
    // Hold the application context to avoid leaking an Activity/short-lived context.
    private val appContext: Context = context.applicationContext

    override fun isPlayStoreUsable(): Boolean {
        return try {
            val info = appContext.packageManager.getApplicationInfo(PLAY_STORE_PACKAGE, 0)
            // A package can be installed but disabled (user or admin). Disabled
            // Play Store can't service billing, so treat it as not usable.
            info.enabled
        } catch (e: PackageManager.NameNotFoundException) {
            // Play Store not installed at all — common on AOSP / non-GMS devices.
            false
        }
    }

    private companion object {
        const val PLAY_STORE_PACKAGE = "com.android.vending"
    }
}
