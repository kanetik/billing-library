package com.kanetik.billing.factory

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PurchasesUpdatedListener
import com.kanetik.billing.ConnectionRetryPolicy
import com.kanetik.billing.InternalConnectionState
import com.kanetik.billing.RetryType
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The default (and currently only) [BillingConnectionFactory] used internally
 * by the library to bridge PBL's
 * [com.android.billingclient.api.BillingClientStateListener] callbacks into a
 * coroutine [Flow] of [InternalConnectionState].
 *
 * Transient `startConnection` failures (those the library classifies as
 * [RetryType.SIMPLE_RETRY] or [RetryType.EXPONENTIAL_RETRY] — e.g.
 * `SERVICE_DISCONNECTED`, `SERVICE_UNAVAILABLE`) are retried internally per
 * the supplied [ConnectionRetryPolicy] before a terminal
 * [InternalConnectionState.Failed] is emitted, so consumers only ever see a
 * connection [com.kanetik.billing.BillingConnectionResult.Error] once the
 * transient condition is actually persistent. See [ConnectionRetryPolicy] for
 * the rationale.
 *
 * The interface exists for v0.2.0 testing-artifact substitution; consumers have
 * no need to swap it. For real customization of the underlying
 * [com.android.billingclient.api.BillingClient] builder, provide a custom
 * [BillingClientFactory] to
 * [BillingRepositoryCreator.create][com.kanetik.billing.BillingRepositoryCreator.create].
 */
internal class CoroutinesBillingConnectionFactory(
    private val context: Context,
    private val billingClientFactory: BillingClientFactory = DefaultBillingClientFactory(),
    private val retryPolicy: ConnectionRetryPolicy = ConnectionRetryPolicy(),
    private val logger: BillingLogger = BillingLogger.Noop
) : BillingConnectionFactory {

    override fun createBillingConnectionFlow(
        listener: PurchasesUpdatedListener
    ): Flow<InternalConnectionState> {
        return callbackFlow<InternalConnectionState> {
            var billingClient: BillingClient? = null

            try {
                connectWithRetry {
                    billingClientFactory.createBillingClient(context, listener).also { billingClient = it }
                }
                // Keep the producer alive after a Connected emit (or after the
                // channel was closed with a terminal exception) until the
                // collector cancels. Cleanup lives in the finally below so it
                // also runs if the scope is cancelled mid-connect, before
                // awaitClose has a chance to register.
                awaitClose { }
            } finally {
                // Always release the client — not just when isReady. A setup
                // that failed or was cancelled mid-connect leaves isReady false
                // yet can still hold a Play service binding, so gating on
                // isReady would leak it. BillingClient.endConnection() is safe
                // to call in any state; guard defensively against an unexpected
                // PBL throw during teardown (best-effort cleanup).
                billingClient?.endQuietly()
            }
        }.catch { error ->
            emit(convertExceptionIntoErrorResult(error))
        }
    }

    /**
     * Drives the bounded connect/retry loop. Emits
     * [InternalConnectionState.Connected] on the first successful setup, or
     * closes the channel with the terminal [BillingException] once the failure
     * is non-transient or the retry budget in [retryPolicy] is exhausted.
     */
    private suspend fun ProducerScope<InternalConnectionState>.connectWithRetry(
        newBillingClient: () -> BillingClient
    ) {
        var attempt = 0
        var exponentialDelay = retryPolicy.exponentialBaseBackoffMillis
        var billingClient: BillingClient? = null

        while (isActive) {
            attempt++
            val client = billingClient ?: newBillingClient().also { billingClient = it }
            val result = withTimeoutOrNull(SETUP_TIMEOUT_MS) { client.awaitSetupResult() }
                ?: setupTimeoutResult.also {
                    client.endQuietly()
                    billingClient = null
                }

            if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                trySend(InternalConnectionState.Connected(client))
                return
            }

            val exception = BillingException.fromResult(result)
            val retryType = exception.retryType
            val retriesRemaining = attempt < retryPolicy.maxAttempts

            if (!retriesRemaining || !retryType.isTransientForConnection()) {
                // Terminal: non-transient classification, or the retry budget
                // is spent. Surface the error to the consumer.
                close(exception)
                return
            }

            val backoff = when (retryType) {
                RetryType.EXPONENTIAL_RETRY ->
                    exponentialDelay.also {
                        // Saturating growth: a large factor / many attempts could
                        // otherwise overflow Long and flip the next delay negative,
                        // which delay() treats as no-wait — silently dropping the
                        // backoff and logging a misleading time. Clamp to
                        // Long.MAX_VALUE on overflow instead.
                        exponentialDelay = try {
                            Math.multiplyExact(exponentialDelay, retryPolicy.exponentialBackoffFactor.toLong())
                        } catch (overflow: ArithmeticException) {
                            Long.MAX_VALUE
                        }
                    }
                else ->
                    retryPolicy.simpleRetryBackoffMillis
            }
            logger.d(
                "Billing connection attempt $attempt failed (retryType=$retryType); " +
                    "retrying in ${backoff}ms (max ${retryPolicy.maxAttempts} attempts)"
            )
            delay(backoff)
        }
    }

    /**
     * Suspends until PBL reports the outcome of a single `startConnection`
     * call. A fresh [BillingClientStateListener] is registered per attempt so
     * each retry awaits its own [BillingResult].
     */
    private suspend fun BillingClient.awaitSetupResult(): BillingResult =
        suspendCancellableCoroutine { cont ->
            startConnection(object : BillingClientStateListener {
                override fun onBillingServiceDisconnected() {
                    if (cont.isActive) {
                        cont.resume(serviceDisconnectedResult)
                    }
                }

                override fun onBillingSetupFinished(result: BillingResult) {
                    // Guard against PBL firing the callback more than once for a
                    // single startConnection — a second resume would throw.
                    if (cont.isActive) {
                        cont.resume(result)
                    }
                }
            })
        }

    /**
     * Whether a [RetryType] warrants an internal *connection* retry.
     * [RetryType.REQUERY_PURCHASE_RETRY] is meaningless before a connection
     * exists, and [RetryType.NONE] is terminal — both surface immediately.
     */
    private fun RetryType.isTransientForConnection(): Boolean =
        this == RetryType.SIMPLE_RETRY || this == RetryType.EXPONENTIAL_RETRY

    private fun BillingClient.endQuietly() {
        try {
            endConnection()
        } catch (e: Exception) {
            logger.d("Ignoring error while ending billing connection", e)
        }
    }

    private fun convertExceptionIntoErrorResult(error: Throwable) = InternalConnectionState.Failed(
        exception = when (error) {
            is BillingException -> error
            // A non-PBL throwable reached the connection flow (e.g. a custom
            // BillingClientFactory threw, or PBL surfaced a non-billing error).
            // WrappedException preserves the original cause; the prior
            // UnknownException(BillingResult()) fallback reported responseCode=OK
            // (no-arg BillingResult) and discarded the throwable, making these
            // failures misleading in logs / Crashlytics.
            else -> BillingException.WrappedException(error)
        }
    )

    private companion object {
        const val SETUP_TIMEOUT_MS: Long = 30_000L

        val setupTimeoutResult: BillingResult = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.SERVICE_UNAVAILABLE)
            .setDebugMessage("Billing setup didn't finish within ${SETUP_TIMEOUT_MS}ms")
            .build()

        val serviceDisconnectedResult: BillingResult = BillingResult.newBuilder()
            .setResponseCode(BillingClient.BillingResponseCode.SERVICE_DISCONNECTED)
            .setDebugMessage("Billing service disconnected before setup finished")
            .build()
    }
}
