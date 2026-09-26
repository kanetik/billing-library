package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.delay

internal enum class RetryProfile(
    val maxAttempts: Int,
    private val firstRetryDelayMs: Long,
    private val backoffFactor: Long
) {
    SINGLE_ATTEMPT(maxAttempts = 1, firstRetryDelayMs = 0L, backoffFactor = 1L),
    INTERACTIVE(maxAttempts = 3, firstRetryDelayMs = 500L, backoffFactor = 1L),
    BACKGROUND(maxAttempts = 5, firstRetryDelayMs = 2_000L, backoffFactor = 2L);

    fun delayBeforeRetry(retryNumber: Int): Long {
        var delayMs = firstRetryDelayMs
        repeat(retryNumber - 1) { delayMs *= backoffFactor }
        return delayMs
    }
}

internal suspend fun <T> retryBillingCall(
    profile: RetryProfile,
    logger: BillingLogger,
    billingResultOf: (T) -> BillingResult,
    call: suspend () -> T
): T {
    var attempt = 0
    while (true) {
        attempt++
        logger.d("attempt $attempt starting")
        val result = call()
        val billingResult = billingResultOf(result)
        if (billingResult.responseCode == BillingResponseCode.OK) {
            logger.d("Success: Operation successful")
            return result
        }
        val exception = BillingException.fromResult(billingResult)
        if (exception.retryType == RetryType.NONE || attempt >= profile.maxAttempts) {
            BillingLoggingUtils.logBillingFailure(
                logger = logger,
                billingResult = billingResult,
                attemptCount = attempt,
                operationContext = "Billing Operation",
                additionalContext = mapOf(
                    "RetryType" to exception.retryType,
                    "RetryProfile" to profile
                )
            )
            throw exception
        }
        delay(profile.delayBeforeRetry(attempt))
    }
}
