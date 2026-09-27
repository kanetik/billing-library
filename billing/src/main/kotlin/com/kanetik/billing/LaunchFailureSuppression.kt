package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import java.util.concurrent.atomic.AtomicLong

internal class LaunchFailureSuppression(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    @Volatile
    private var armedAtMs: Long? = null

    private val generation = AtomicLong(0L)

    fun cancel(): Long {
        armedAtMs = null
        return generation.incrementAndGet()
    }

    fun arm(attempt: Long) {
        if (attempt == generation.get()) {
            armedAtMs = clock()
        }
    }

    fun consume(responseCode: Int): Boolean {
        val armedAt = armedAtMs ?: return false
        val withinWindow = clock() - armedAt <= WINDOW_MS
        val matches = withinWindow && responseCode == BillingResponseCode.BILLING_UNAVAILABLE
        if (matches || !withinWindow) {
            armedAtMs = null
        }
        return matches
    }

    private companion object {
        const val WINDOW_MS = 500L
    }
}
