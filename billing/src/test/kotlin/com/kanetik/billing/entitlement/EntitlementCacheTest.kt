package com.kanetik.billing.entitlement

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingResult
import com.android.billingclient.api.Purchase
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.FlowOutcome
import com.kanetik.billing.OwnedPurchases
import com.kanetik.billing.PurchaseEvent
import com.kanetik.billing.PurchaseRevoked
import com.kanetik.billing.RevocationReason
import com.kanetik.billing.billingResult
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.fakePurchase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.TimeUnit

@OptIn(ExperimentalCoroutinesApi::class)
class EntitlementCacheTest {

    // Multi-entitlement-friendly: a simple sealed pair of keys so tests cover
    // the per-key semantics (per-key revocation matching, hydration of
    // multiple snapshots, etc.). Single-key tests just use ONE.
    private enum class TestKey { ONE, TWO }

    private val productIdOne = "shop_unlock_one"
    private val productIdTwo = "shop_unlock_two"

    private val keySelector: (Purchase) -> TestKey? = { purchase ->
        when {
            productIdOne in purchase.products -> TestKey.ONE
            productIdTwo in purchase.products -> TestKey.TWO
            else -> null
        }
    }

    @Test
    fun `Live with matching purchase transitions that key to Granted and persists snapshot`() = runTest {
        val (cache, updates, storage, _, job) = newCache()

        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = productIdOne, purchaseToken = "tok1"))))
        runCurrent()

        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)
        assertThat(cache.state.value[TestKey.TWO]).isNull()
        assertThat(storage.lastWritten(TestKey.ONE)).isEqualTo(
            EntitlementSnapshot(
                isEntitled = true,
                confirmedAtMs = INITIAL_CLOCK,
                purchaseToken = "tok1",
            ),
        )

        job.cancelAndJoin()
    }

    @Test
    fun `Recovered with matching purchase transitions that key to Granted and persists snapshot`() = runTest {
        val (cache, updates, storage, _, job) = newCache()

        updates.emit(OwnedPurchases.Recovered(listOf(fakePurchase(productId = productIdOne, purchaseToken = "tok2"))))
        runCurrent()

        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)
        assertThat(storage.lastWritten(TestKey.ONE)?.purchaseToken).isEqualTo("tok2")

        job.cancelAndJoin()
    }

    @Test
    fun `Snapshot with matching purchase transitions that key to Granted and persists snapshot`() = runTest {
        val (cache, updates, storage, _, job) = newCache()

        updates.emit(OwnedPurchases.Snapshot(listOf(fakePurchase(productId = productIdOne, purchaseToken = "tok-snap"))))
        runCurrent()

        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)
        assertThat(storage.lastWritten(TestKey.ONE)?.purchaseToken).isEqualTo("tok-snap")

        job.cancelAndJoin()
    }

    @Test
    fun `Live containing multiple matching purchases grants all matching keys`() = runTest {
        val (cache, updates, storage, _, job) = newCache()

        updates.emit(
            OwnedPurchases.Live(
                listOf(
                    fakePurchase(productId = productIdOne, purchaseToken = "tok-one"),
                    fakePurchase(productId = productIdTwo, purchaseToken = "tok-two"),
                ),
            ),
        )
        runCurrent()

        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)
        assertThat(cache.state.value[TestKey.TWO]).isEqualTo(EntitlementState.Granted)
        assertThat(storage.lastWritten(TestKey.ONE)?.purchaseToken).isEqualTo("tok-one")
        assertThat(storage.lastWritten(TestKey.TWO)?.purchaseToken).isEqualTo("tok-two")

        job.cancelAndJoin()
    }

    @Test
    fun `Recovered with no matching purchase does NOT revoke a Granted snapshot`() = runTest {
        val storage = FakeEntitlementStorage(
            initial = mapOf(
                TestKey.ONE to EntitlementSnapshot(
                    isEntitled = true,
                    confirmedAtMs = INITIAL_CLOCK - 1_000L,
                    purchaseToken = "tok-prior",
                ),
            ),
        )
        val (cache, updates, _, _, job) = newCache(storage = storage)
        runCurrent()
        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)

        updates.emit(OwnedPurchases.Recovered(emptyList()))
        runCurrent()
        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)

        updates.emit(OwnedPurchases.Recovered(listOf(fakePurchase(productId = "different-product"))))
        runCurrent()
        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)

        job.cancelAndJoin()
    }

    @Test
    fun `Snapshot with no matching purchase does NOT revoke a Granted snapshot`() = runTest {
        val storage = FakeEntitlementStorage(
            initial = mapOf(
                TestKey.ONE to EntitlementSnapshot(
                    isEntitled = true,
                    confirmedAtMs = INITIAL_CLOCK - 1_000L,
                    purchaseToken = "tok-prior",
                ),
            ),
        )
        val (cache, updates, _, _, job) = newCache(storage = storage)
        runCurrent()
        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)

        updates.emit(OwnedPurchases.Snapshot(emptyList()))
        runCurrent()
        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)

        job.cancelAndJoin()
    }

    @Test
    fun `PurchaseRevoked matching a key's cached token revokes only that key`() = runTest {
        val (cache, updates, _, _, job) = newCache()
        // Establish Granted on both keys.
        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = productIdOne, purchaseToken = "tok-one"))))
        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = productIdTwo, purchaseToken = "tok-two"))))
        runCurrent()
        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)
        assertThat(cache.state.value[TestKey.TWO]).isEqualTo(EntitlementState.Granted)

        updates.emit(PurchaseRevoked(purchaseToken = "tok-one", reason = RevocationReason.Refunded))
        runCurrent()

        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Revoked)
        assertThat(cache.state.value[TestKey.TWO]).isEqualTo(EntitlementState.Granted)

        job.cancelAndJoin()
    }

    @Test
    fun `PurchaseRevoked for a different token does not affect cached state`() = runTest {
        val (cache, updates, _, _, job) = newCache()
        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = productIdOne, purchaseToken = "tok-one"))))
        runCurrent()
        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)

        updates.emit(PurchaseRevoked(purchaseToken = "some-other-tok", reason = RevocationReason.Chargeback))
        runCurrent()

        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)

        job.cancelAndJoin()
    }

    @Test
    fun `UserBillingError leaves a Granted key untouched`() = runTest {
        val (cache, updates, _, _, job) = newCache()
        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = productIdOne))))
        runCurrent()

        updates.emit(FlowOutcome.UserBillingError(emptyList(), billingResult(BillingResponseCode.BILLING_UNAVAILABLE)))
        runCurrent()

        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)
        job.cancelAndJoin()
    }

    @Test
    fun `long-held Granted key survives Failure NetworkError and nothing is persisted as revoked`() = runTest {
        val longHeld = EntitlementSnapshot(
            isEntitled = true,
            confirmedAtMs = INITIAL_CLOCK - TimeUnit.DAYS.toMillis(365),
            purchaseToken = "tok-long-held",
        )
        val storage = FakeEntitlementStorage(initial = mapOf(TestKey.ONE to longHeld))
        val (cache, updates, _, _, job) = newCache(storage = storage)
        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)

        updates.emit(FlowOutcome.Failure(networkErrorException(), emptyList(), billingResult(BillingResponseCode.NETWORK_ERROR)))
        runCurrent()

        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)
        assertThat(storage.lastWritten(TestKey.ONE)).isEqualTo(longHeld)
        job.cancelAndJoin()
    }

    @Test
    fun `long-held Granted key survives Failure ERROR and nothing is persisted as revoked`() = runTest {
        val longHeld = EntitlementSnapshot(
            isEntitled = true,
            confirmedAtMs = INITIAL_CLOCK - TimeUnit.DAYS.toMillis(365),
            purchaseToken = "tok-long-held",
        )
        val storage = FakeEntitlementStorage(initial = mapOf(TestKey.ONE to longHeld))
        val (cache, updates, _, _, job) = newCache(storage = storage)
        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)

        updates.emit(FlowOutcome.Failure(fatalErrorException(), emptyList(), billingResult(BillingResponseCode.ERROR)))
        runCurrent()

        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)
        assertThat(storage.lastWritten(TestKey.ONE)).isEqualTo(longHeld)
        job.cancelAndJoin()
    }

    @Test
    fun `long-held Granted keys survive a Failure for a different in-flight product`() = runTest {
        val longHeldOne = EntitlementSnapshot(
            isEntitled = true,
            confirmedAtMs = INITIAL_CLOCK - TimeUnit.DAYS.toMillis(365),
            purchaseToken = "tok-one-long-held",
        )
        val storage = FakeEntitlementStorage(initial = mapOf(TestKey.ONE to longHeldOne))
        val (cache, updates, _, _, job) = newCache(storage = storage)
        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)
        assertThat(cache.state.value[TestKey.TWO]).isNull()

        updates.emit(FlowOutcome.Failure(networkErrorException(), emptyList(), billingResult(BillingResponseCode.NETWORK_ERROR)))
        runCurrent()

        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)
        assertThat(cache.state.value[TestKey.TWO]).isNull()
        assertThat(storage.lastWritten(TestKey.ONE)).isEqualTo(longHeldOne)
        job.cancelAndJoin()
    }

    @Test
    fun `Granted snapshots persist across cache instances via storage for each key`() = runTest {
        val storage = FakeEntitlementStorage<TestKey>()

        val firstUpdates = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
        val firstCache = EntitlementCache(
            purchasesUpdates = firstUpdates,
            storage = storage,
            productKeySelector = keySelector,
            clock = { INITIAL_CLOCK },
        )
        val firstJob = firstCache.start(this)
        runCurrent()
        firstUpdates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = productIdOne, purchaseToken = "tok-rt-one"))))
        firstUpdates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = productIdTwo, purchaseToken = "tok-rt-two"))))
        runCurrent()
        assertThat(firstCache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)
        assertThat(firstCache.state.value[TestKey.TWO]).isEqualTo(EntitlementState.Granted)
        firstJob.cancelAndJoin()

        val secondUpdates = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
        val secondCache = EntitlementCache(
            purchasesUpdates = secondUpdates,
            storage = storage,
            productKeySelector = keySelector,
            clock = { INITIAL_CLOCK + 1_000L },
        )
        val secondJob = secondCache.start(this)
        runCurrent()
        assertThat(secondCache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)
        assertThat(secondCache.state.value[TestKey.TWO]).isEqualTo(EntitlementState.Granted)
        assertThat(storage.lastWritten(TestKey.ONE)?.purchaseToken).isEqualTo("tok-rt-one")
        assertThat(storage.lastWritten(TestKey.TWO)?.purchaseToken).isEqualTo("tok-rt-two")
        secondJob.cancelAndJoin()
    }

    @Test
    fun `Live with matching product but UNSPECIFIED_STATE does NOT grant entitlement`() = runTest {
        val (cache, updates, _, _, job) = newCache()
        updates.emit(
            OwnedPurchases.Live(
                listOf(
                    fakePurchase(
                        productId = productIdOne,
                        purchaseToken = "unspecified-tok",
                        purchaseState = Purchase.PurchaseState.UNSPECIFIED_STATE,
                    ),
                ),
            ),
        )
        runCurrent()
        assertThat(cache.state.value[TestKey.ONE]).isNull()

        updates.emit(
            OwnedPurchases.Live(
                listOf(
                    fakePurchase(
                        productId = productIdOne,
                        purchaseToken = "purchased-tok",
                        purchaseState = Purchase.PurchaseState.PURCHASED,
                    ),
                ),
            ),
        )
        runCurrent()
        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)

        job.cancelAndJoin()
    }

    @Test
    fun `start called twice returns the same active Job`() = runTest {
        val updates = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
        val cache = EntitlementCache(
            purchasesUpdates = updates,
            storage = FakeEntitlementStorage<TestKey>(),
            productKeySelector = keySelector,
            clock = { INITIAL_CLOCK },
        )
        val first = cache.start(this)
        val second = cache.start(this)
        assertThat(second).isSameInstanceAs(first)
        assertThat(first.isActive).isTrue()
        first.cancelAndJoin()
    }

    @Test
    fun `restart drains stale buffered snapshots from cancelled session`() = runTest {
        val storage = FakeEntitlementStorage<TestKey>()
        val updates = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
        val cache = EntitlementCache(
            purchasesUpdates = updates,
            storage = storage,
            productKeySelector = keySelector,
            clock = { INITIAL_CLOCK },
        )
        val first = cache.start(this)
        runCurrent()
        cache.reduce(
            OwnedPurchases.Live(
                listOf(
                    fakePurchase(
                        productId = productIdOne,
                        purchaseToken = "doomed-tok",
                        purchaseState = Purchase.PurchaseState.PURCHASED,
                    ),
                ),
            ),
        )
        first.cancelAndJoin()

        storage.put(
            TestKey.ONE,
            EntitlementSnapshot(
                isEntitled = true,
                confirmedAtMs = INITIAL_CLOCK + 5_000L,
                purchaseToken = "fresh-tok",
            ),
        )
        val second = cache.start(this)
        runCurrent()
        repeat(3) { runCurrent() }

        assertThat(storage.lastWritten(TestKey.ONE)?.purchaseToken).isEqualTo("fresh-tok")

        second.cancelAndJoin()
    }

    @Test
    fun `start is restartable after the previous Job is cancelled`() = runTest {
        val updates = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
        val cache = EntitlementCache(
            purchasesUpdates = updates,
            storage = FakeEntitlementStorage<TestKey>(),
            productKeySelector = keySelector,
            clock = { INITIAL_CLOCK },
        )
        val first = cache.start(this)
        first.cancelAndJoin()
        assertThat(first.isActive).isFalse()

        val second = cache.start(this)
        runCurrent()
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.isActive).isTrue()

        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = productIdOne, purchaseToken = "after-restart"))))
        runCurrent()
        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)

        second.cancelAndJoin()
    }

    @Test
    fun `Failure while no key is Granted is a no-op`() = runTest {
        val (cache, updates, _, _, job) = newCache()

        updates.emit(FlowOutcome.Failure(networkErrorException(), emptyList(), billingResult(BillingResponseCode.NETWORK_ERROR)))
        runCurrent()

        assertThat(cache.state.value).isEmpty()
        job.cancelAndJoin()
    }

    @Test
    fun `Live of an unrelated product does not revoke an existing Granted state`() = runTest {
        val (cache, updates, _, _, job) = newCache()
        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = productIdOne))))
        runCurrent()
        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)

        // A consumable currency pack arrives in the Live stream — the selector
        // returns null for it, so it doesn't grant any key but also must not
        // revoke the already-granted key.
        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = "coins_pack_50"))))
        runCurrent()

        assertThat(cache.state.value[TestKey.ONE]).isEqualTo(EntitlementState.Granted)
        job.cancelAndJoin()
    }

    @Test
    fun `stateFor exposes a per-key Flow that surfaces absent keys as Revoked`() = runTest {
        val (cache, updates, _, _, job) = newCache()
        // Sanity: TWO is absent from the map.
        assertThat(cache.state.value[TestKey.TWO]).isNull()

        val collected = mutableListOf<EntitlementState>()
        val collectorJob = launch { cache.stateFor(TestKey.TWO).collect { collected.add(it) } }
        runCurrent()
        assertThat(collected).containsExactly(EntitlementState.Revoked)

        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = productIdTwo))))
        runCurrent()
        assertThat(collected).containsExactly(EntitlementState.Revoked, EntitlementState.Granted).inOrder()

        collectorJob.cancelAndJoin()
        job.cancelAndJoin()
    }

    // -- Test helpers --------------------------------------------------------

    private data class CacheUnderTest(
        val cache: EntitlementCache<TestKey>,
        val updates: MutableSharedFlow<PurchaseEvent>,
        val storage: FakeEntitlementStorage<TestKey>,
        val clock: () -> Long,
        val job: Job,
    )

    private suspend fun TestScope.newCache(
        storage: FakeEntitlementStorage<TestKey> = FakeEntitlementStorage(),
        clock: () -> Long = { INITIAL_CLOCK },
    ): CacheUnderTest {
        val updates = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
        val cache = EntitlementCache(
            purchasesUpdates = updates,
            storage = storage,
            productKeySelector = keySelector,
            clock = clock,
        )
        val job = cache.start(this)
        runCurrent()
        return CacheUnderTest(cache, updates, storage, clock, job)
    }

    private fun networkErrorException(): BillingException =
        BillingException.NetworkErrorException(
            BillingResult.newBuilder().setResponseCode(BillingResponseCode.NETWORK_ERROR).build(),
        )

    private fun fatalErrorException(): BillingException =
        BillingException.FatalErrorException(
            BillingResult.newBuilder().setResponseCode(BillingResponseCode.ERROR).build(),
        )

    companion object {
        private const val INITIAL_CLOCK = 1_000_000L
    }
}

private class FakeEntitlementStorage<K : Any>(
    initial: Map<K, EntitlementSnapshot> = emptyMap(),
) : EntitlementStorage<K> {
    private val store: MutableMap<K, EntitlementSnapshot> = HashMap(initial)

    fun lastWritten(key: K): EntitlementSnapshot? = store[key]

    fun put(key: K, snapshot: EntitlementSnapshot) {
        store[key] = snapshot
    }

    override suspend fun readAll(): Map<K, EntitlementSnapshot> = HashMap(store)

    override suspend fun write(key: K, snapshot: EntitlementSnapshot) {
        store[key] = snapshot
    }
}
