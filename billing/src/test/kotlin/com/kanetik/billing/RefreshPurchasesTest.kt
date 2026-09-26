package com.kanetik.billing

import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.factory.BillingConnectionFactory
import com.kanetik.billing.logging.BillingLogger
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class RefreshPurchasesTest {

    @Test
    fun `refreshPurchases retries a transient query with the BACKGROUND profile's backoff`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(Op.QUERY_PURCHASES, BillingResponseCode.NETWORK_ERROR, BillingResponseCode.NETWORK_ERROR, BillingResponseCode.OK)
        }
        val repository = repositoryOver(play)

        val outcome = timed { repository.refreshPurchases() }

        assertThat(outcome.result.exceptionOrNull()).isNull()
        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(3)
        assertThat(outcome.elapsedMs).isEqualTo(2000L + 4000L)
    }

    @Test
    fun `refreshPurchases queries only INAPP when SUBS is unsupported`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
        }
        val repository = repositoryOver(play)

        repository.refreshPurchases()

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(1)
    }

    @Test
    fun `refreshPurchases queries both INAPP and SUBS when SUBS is supported`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.OK)
        }
        val repository = repositoryOver(play)

        repository.refreshPurchases()

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(2)
    }

    @Test
    fun `refreshPurchases rethrows a terminal query failure rather than swallowing it`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(Op.QUERY_PURCHASES, BillingResponseCode.DEVELOPER_ERROR)
        }
        val repository = repositoryOver(play)

        val outcome = timed { repository.refreshPurchases() }

        assertThat(outcome.result.exceptionOrNull()).isInstanceOf(BillingException.DeveloperErrorException::class.java)
    }

    @Test
    fun `refreshPurchases includes already-acknowledged purchases in the emitted Snapshot`() = runTest {
        val acked = fakePurchase(
            productId = "p-acked",
            purchaseToken = "token-acked",
            purchaseState = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = true
        )
        val unacked = fakePurchase(
            productId = "p-unacked",
            purchaseToken = "token-unacked",
            purchaseState = Purchase.PurchaseState.PURCHASED,
            isAcknowledged = false
        )
        val client = mockOwningClient(inApp = listOf(acked, unacked), subsSupported = false)
        val storage = BillingClientStorage(
            billingFactory = SingleEmissionFactory(InternalConnectionState.Connected(client)),
            logger = BillingLogger.Noop,
            connectionShareScope = backgroundScope,
            ioDispatcher = UnconfinedTestDispatcher(testScheduler),
            recoverPurchasesOnConnect = false
        )
        val snapshotDeferred = async {
            storage.purchasesUpdateFlow.first { it is OwnedPurchases.Snapshot } as OwnedPurchases.Snapshot
        }
        runCurrent()

        storage.refreshOwnedPurchases(client)

        val snapshot = snapshotDeferred.await()
        assertThat(snapshot.purchases).containsExactly(acked, unacked)
    }

    private fun mockOwningClient(
        inApp: List<Purchase>,
        subs: List<Purchase> = emptyList(),
        subsSupported: Boolean
    ): BillingClient = mockk<BillingClient>(relaxed = true).also { client ->
        every {
            client.isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
        } returns BillingResult.newBuilder().setResponseCode(
            if (subsSupported) BillingResponseCode.OK else BillingResponseCode.FEATURE_NOT_SUPPORTED
        ).build()

        val listenerSlot = slot<PurchasesResponseListener>()
        var callIndex = 0
        every {
            client.queryPurchasesAsync(any(), capture(listenerSlot))
        } answers {
            val listener = listenerSlot.captured
            val purchasesForCall = if (callIndex == 0) inApp else subs
            callIndex++
            val ok = BillingResult.newBuilder().setResponseCode(BillingResponseCode.OK).build()
            listener.onQueryPurchasesResponse(ok, purchasesForCall)
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
}
