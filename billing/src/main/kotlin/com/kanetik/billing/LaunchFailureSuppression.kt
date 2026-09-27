package com.kanetik.billing

import java.util.concurrent.atomic.AtomicLong

internal class LaunchFailureSuppression(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000L }
) {
    private data class Armed(val responseCode: Int, val armedAtMs: Long)

    @Volatile
    private var armed: Armed? = null

    private val generation = AtomicLong(0L)

    fun cancel(): Long {
        armed = null
        return generation.incrementAndGet()
    }

    fun arm(responseCode: Int, attempt: Long) {
        if (attempt == generation.get()) {
            armed = Armed(responseCode, clock())
        }
    }

    fun consume(responseCode: Int): Boolean {
        val pending = armed ?: return false
        val withinWindow = clock() - pending.armedAtMs <= WINDOW_MS
        val matches = withinWindow && pending.responseCode == responseCode
        if (matches || !withinWindow) {
            armed = null
        }
        return matches
    }

    private companion object {
        const val WINDOW_MS = 500L
    }
}
