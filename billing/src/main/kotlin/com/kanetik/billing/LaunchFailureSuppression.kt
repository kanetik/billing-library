package com.kanetik.billing

internal class LaunchFailureSuppression(
    private val clock: () -> Long = System::currentTimeMillis
) {
    private data class Armed(val responseCode: Int, val armedAtMs: Long)

    @Volatile
    private var armed: Armed? = null

    fun arm(responseCode: Int) {
        armed = Armed(responseCode, clock())
    }

    fun cancel() {
        armed = null
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
