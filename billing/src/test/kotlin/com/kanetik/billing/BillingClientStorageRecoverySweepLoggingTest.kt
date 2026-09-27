package com.kanetik.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.factory.BillingConnectionFactory
import com.kanetik.billing.logging.BillingLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillingClientStorageRecoverySweepLoggingTest {

    @Test
    fun `a retry-exhausted query failure is logged exactly once, not twice`() = runTest {
        val captor = CapturingLogger()
        val client = mockBillingClientFailingWith(BillingClient.BillingResponseCode.DEVELOPER_ERROR)
        val factory = SingleEmissionFactory(InternalConnectionState.Connected(client))

        val storage = BillingClientStorage(
            billingFactory = factory,
            logger = captor,
            connectionShareScope = backgroundScope + UnconfinedTestDispatcher(testScheduler),
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            recoverPurchasesOnConnect = true
        )

        val collected = mutableListOf<PurchaseEvent>()
        val job = launch { storage.purchasesUpdateFlow.collect { collected += it } }
        val connJob = launch { storage.connectionFlow.collect {} }
        repeat(3) { yield(); advanceUntilIdle() }
        job.cancel()
        connJob.cancel()

        assertThat(captor.errors).hasSize(1)
        assertThat(captor.warnings.count { it.contains("query failed") }).isEqualTo(0)
    }

    private fun mockBillingClientFailingWith(responseCode: Int): BillingClient =
        mockk<BillingClient>(relaxed = true).also { client ->
            every {
                client.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
            } returns BillingResult.newBuilder()
                .setResponseCode(BillingClient.BillingResponseCode.FEATURE_NOT_SUPPORTED)
                .build()

            val listenerSlot = slot<PurchasesResponseListener>()
            every {
                client.queryPurchasesAsync(any(), capture(listenerSlot))
            } answers {
                val listener = listenerSlot.captured
                val failure = BillingResult.newBuilder().setResponseCode(responseCode).build()
                listener.onQueryPurchasesResponse(failure, emptyList<Purchase>())
            }
        }

    private class SingleEmissionFactory(
        private val initialState: InternalConnectionState
    ) : BillingConnectionFactory {
        private val flow = MutableSharedFlow<InternalConnectionState>(replay = 1, extraBufferCapacity = 4)

        init {
            check(flow.tryEmit(initialState))
        }

        override fun createBillingConnectionFlow(
            listener: PurchasesUpdatedListener
        ): Flow<InternalConnectionState> = flow
    }

    private class CapturingLogger : BillingLogger {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        override fun d(message: String, throwable: Throwable?) = Unit
        override fun w(message: String, throwable: Throwable?) {
            warnings += message
        }
        override fun e(message: String, throwable: Throwable?) {
            errors += message
        }
    }
}
