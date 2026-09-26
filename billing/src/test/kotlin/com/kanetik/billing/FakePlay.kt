@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kanetik.billing

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.AcknowledgePurchaseParams
import com.android.billingclient.api.AcknowledgePurchaseResponseListener
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingClientStateListener
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.ConsumeParams
import com.android.billingclient.api.ConsumeResponseListener
import com.android.billingclient.api.ProductDetailsResponseListener
import com.android.billingclient.api.PurchasesResponseListener
import com.android.billingclient.api.PurchasesUpdatedListener
import com.android.billingclient.api.QueryProductDetailsParams
import com.android.billingclient.api.QueryProductDetailsResult
import com.android.billingclient.api.QueryPurchasesParams
import com.kanetik.billing.factory.BillingClientFactory
import com.kanetik.billing.factory.CoroutinesBillingConnectionFactory
import com.kanetik.billing.logging.BillingLogger
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

internal enum class Op {
    QUERY_PRODUCT_DETAILS, QUERY_PURCHASES, ACKNOWLEDGE, CONSUME, LAUNCH_FLOW, IS_FEATURE_SUPPORTED
}

internal val codeNames: Map<Int, String> = mapOf(
    BillingResponseCode.OK to "OK",
    BillingResponseCode.USER_CANCELED to "USER_CANCELED",
    BillingResponseCode.SERVICE_UNAVAILABLE to "SERVICE_UNAVAILABLE",
    BillingResponseCode.BILLING_UNAVAILABLE to "BILLING_UNAVAILABLE",
    BillingResponseCode.ITEM_UNAVAILABLE to "ITEM_UNAVAILABLE",
    BillingResponseCode.DEVELOPER_ERROR to "DEVELOPER_ERROR",
    BillingResponseCode.ERROR to "ERROR",
    BillingResponseCode.ITEM_ALREADY_OWNED to "ITEM_ALREADY_OWNED",
    BillingResponseCode.ITEM_NOT_OWNED to "ITEM_NOT_OWNED",
    BillingResponseCode.NETWORK_ERROR to "NETWORK_ERROR",
    BillingResponseCode.SERVICE_DISCONNECTED to "SERVICE_DISCONNECTED",
    BillingResponseCode.FEATURE_NOT_SUPPORTED to "FEATURE_NOT_SUPPORTED"
)

internal val simpleRetryCodes = setOf(BillingResponseCode.SERVICE_DISCONNECTED)
internal val exponentialRetryCodes = setOf(
    BillingResponseCode.SERVICE_UNAVAILABLE,
    BillingResponseCode.ERROR,
    BillingResponseCode.NETWORK_ERROR
)

internal fun billingResult(code: Int): BillingResult =
    BillingResult.newBuilder().setResponseCode(code).build()

internal class FakePlay {
    val connectCodes = ArrayDeque<Int?>()
    val clients = mutableListOf<BillingClient>()
    val endedClients = mutableSetOf<BillingClient>()
    val stateListeners = mutableListOf<BillingClientStateListener>()
    var startConnectionCount = 0
        private set

    private val scripts = mutableMapOf<Op, ArrayDeque<Int>>()
    private val callLog = mutableListOf<Pair<Op, BillingClient>>()

    fun script(op: Op, vararg codes: Int) {
        scripts[op] = ArrayDeque(codes.toList())
    }

    fun calls(op: Op): Int = callLog.count { it.first == op }

    fun calls(op: Op, client: BillingClient): Int = callLog.count { it.first == op && it.second === client }

    val clientFactory = object : BillingClientFactory {
        override fun createBillingClient(context: Context, listener: PurchasesUpdatedListener): BillingClient =
            newClient()
    }

    private fun respond(op: Op, client: BillingClient): BillingResult {
        callLog += op to client
        if (client in endedClients) return billingResult(BillingResponseCode.SERVICE_DISCONNECTED)
        val queue = scripts[op] ?: return billingResult(BillingResponseCode.OK)
        return billingResult(if (queue.size > 1) queue.removeFirst() else queue.first())
    }

    private fun newClient(): BillingClient {
        val client = mockk<BillingClient>(relaxed = true)
        clients += client
        every { client.startConnection(any()) } answers {
            startConnectionCount++
            val listener = firstArg<BillingClientStateListener>()
            stateListeners += listener
            val code = if (connectCodes.isEmpty()) BillingResponseCode.OK else connectCodes.removeFirst()
            if (code != null) listener.onBillingSetupFinished(billingResult(code))
        }
        every { client.endConnection() } answers { endedClients += client }
        every { client.isReady } answers { client !in endedClients }
        every { client.queryPurchasesAsync(any<QueryPurchasesParams>(), any()) } answers {
            secondArg<PurchasesResponseListener>()
                .onQueryPurchasesResponse(respond(Op.QUERY_PURCHASES, client), emptyList())
        }
        every { client.queryProductDetailsAsync(any<QueryProductDetailsParams>(), any()) } answers {
            val details = mockk<QueryProductDetailsResult> {
                every { productDetailsList } returns emptyList()
                every { unfetchedProductList } returns emptyList()
            }
            secondArg<ProductDetailsResponseListener>()
                .onProductDetailsResponse(respond(Op.QUERY_PRODUCT_DETAILS, client), details)
        }
        every { client.acknowledgePurchase(any(), any()) } answers {
            secondArg<AcknowledgePurchaseResponseListener>()
                .onAcknowledgePurchaseResponse(respond(Op.ACKNOWLEDGE, client))
        }
        every { client.consumeAsync(any(), any()) } answers {
            secondArg<ConsumeResponseListener>().onConsumeResponse(respond(Op.CONSUME, client), "token")
        }
        every { client.launchBillingFlow(any(), any()) } answers { respond(Op.LAUNCH_FLOW, client) }
        every { client.isFeatureSupported(any()) } answers { respond(Op.IS_FEATURE_SUPPORTED, client) }
        return client
    }
}

internal fun TestScope.storageOver(
    play: FakePlay,
    policy: ConnectionRetryPolicy = ConnectionRetryPolicy(),
    recoverPurchasesOnConnect: Boolean = false
): BillingClientStorage = BillingClientStorage(
    billingFactory = CoroutinesBillingConnectionFactory(
        context = mockk(relaxed = true),
        billingClientFactory = play.clientFactory,
        retryPolicy = policy,
        logger = BillingLogger.Noop
    ),
    logger = BillingLogger.Noop,
    connectionShareScope = backgroundScope,
    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    recoverPurchasesOnConnect = recoverPurchasesOnConnect
)

internal fun TestScope.repositoryOver(
    play: FakePlay,
    policy: ConnectionRetryPolicy = ConnectionRetryPolicy()
): DefaultBillingRepository = DefaultBillingRepository(
    billingClientStorage = storageOver(play, policy),
    logger = BillingLogger.Noop,
    ioDispatcher = UnconfinedTestDispatcher(testScheduler),
    uiDispatcher = UnconfinedTestDispatcher(testScheduler)
)

internal suspend fun DefaultBillingRepository.perform(op: Op): Any? = when (op) {
    Op.QUERY_PRODUCT_DETAILS -> queryProductDetails(mockk<QueryProductDetailsParams>(relaxed = true))
    Op.QUERY_PURCHASES -> queryPurchases(
        QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()
    )
    Op.ACKNOWLEDGE -> acknowledgePurchase(mockk<AcknowledgePurchaseParams>(relaxed = true))
    Op.CONSUME -> consumePurchase(mockk<ConsumeParams>(relaxed = true))
    Op.LAUNCH_FLOW -> launchFlow(mockk<Activity>(relaxed = true), mockk(relaxed = true))
    Op.IS_FEATURE_SUPPORTED -> isFeatureSupported(BillingClient.FeatureType.SUBSCRIPTIONS)
}

internal class Timed(val result: Result<Any?>, val elapsedMs: Long)

internal suspend fun TestScope.timed(block: suspend () -> Any?): Timed {
    val start = testScheduler.currentTime
    val result = runCatching { block() }
    return Timed(result, testScheduler.currentTime - start)
}
