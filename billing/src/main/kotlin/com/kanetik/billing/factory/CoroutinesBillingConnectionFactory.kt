package com.kanetik.billing.factory

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.PurchasesUpdatedListener
import com.kanetik.billing.InternalConnectionState
import com.kanetik.billing.exception.BillingException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive

/**
 * The default (and currently only) [BillingConnectionFactory] used internally
 * by the library to bridge PBL's
 * [com.android.billingclient.api.BillingClientStateListener] callbacks into a
 * coroutine [Flow] of [InternalConnectionState].
 *
 * The interface exists for v0.2.0 testing-artifact substitution; consumers have
 * no need to swap it. For real customization of the underlying
 * [com.android.billingclient.api.BillingClient] builder, provide a custom
 * [BillingClientFactory] to
 * [BillingRepositoryCreator.create][com.kanetik.billing.BillingRepositoryCreator.create].
 */
internal class CoroutinesBillingConnectionFactory(
    private val context: Context,
    private val billingClientFactory: BillingClientFactory = DefaultBillingClientFactory()
) : BillingConnectionFactory {

    override fun createBillingConnectionFlow(
        listener: PurchasesUpdatedListener
    ): Flow<InternalConnectionState> {
        return callbackFlow<InternalConnectionState> {
            val billingClient = billingClientFactory.createBillingClient(context, listener)

            billingClient.startConnection(object : BillingClientStateListener {
                override fun onBillingServiceDisconnected() {
                    // With automatic service reconnection in Billing Library 8,
                    // the client will handle reconnection automatically
                    // No need to close the flow with an exception
                }

                override fun onBillingSetupFinished(result: BillingResult) {
                    if (isActive) {
                        if (result.responseCode == BillingClient.BillingResponseCode.OK) {
                            trySend(InternalConnectionState.Connected(billingClient))
                        } else {
                            close(BillingException.fromResult(result))
                        }
                    } else {
                        billingClient.endConnectionIfConnected()
                    }
                }
            })
            awaitClose {
                billingClient.endConnectionIfConnected()
            }
        }.catch { error ->
            emit(convertExceptionIntoErrorResult(error))
        }
    }

    private fun convertExceptionIntoErrorResult(error: Throwable) = InternalConnectionState.Failed(
        exception = when (error) {
            is BillingException -> error
            else -> BillingException.UnknownException(BillingResult())
        }
    )

    private fun BillingClient.endConnectionIfConnected() {
        if (isReady) {
            endConnection()
        }
    }
}
