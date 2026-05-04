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
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.fakePurchase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class EntitlementCacheTest {

    private val premiumProductId = "premium_lifetime"
    private val premiumPredicate: (Purchase) -> Boolean = { it.products.contains(premiumProductId) }

    @Test
    fun `Live with matching purchase transitions to Granted and persists snapshot`() = runTest {
        val (cache, updates, storage, _, job) = newCache()

        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = premiumProductId, purchaseToken = "tok1"))))
        runCurrent()

        assertThat(cache.state.value).isEqualTo(EntitlementState.Granted)
        assertThat(storage.lastWritten).isEqualTo(
            EntitlementSnapshot(
                isEntitled = true,
                confirmedAtMs = INITIAL_CLOCK,
                purchaseToken = "tok1",
            ),
        )

        job.cancelAndJoin()
    }

    @Test
    fun `Recovered with matching purchase transitions to Granted and persists snapshot`() = runTest {
        val (cache, updates, storage, _, job) = newCache()

        updates.emit(OwnedPurchases.Recovered(listOf(fakePurchase(productId = premiumProductId, purchaseToken = "tok2"))))
        runCurrent()

        assertThat(cache.state.value).isEqualTo(EntitlementState.Granted)
        assertThat(storage.lastWritten).isEqualTo(
            EntitlementSnapshot(
                isEntitled = true,
                confirmedAtMs = INITIAL_CLOCK,
                purchaseToken = "tok2",
            ),
        )

        job.cancelAndJoin()
    }

    @Test
    fun `Recovered with no matching purchase does NOT revoke a Granted snapshot`() = runTest {
        // Recovered emits only PURCHASED && !isAcknowledged, filtered against
        // the library's acknowledgedTokens set (PR #10). An already-acked
        // entitling purchase will never appear in Recovered; treating an
        // empty Recovered as "Play revoked the entitlement" would falsely
        // revoke entitled-but-acked users. The cache treats Recovered as
        // grant-only — see EntitlementCache class KDoc.
        val storage = FakeEntitlementStorage(
            initial = EntitlementSnapshot(
                isEntitled = true,
                confirmedAtMs = INITIAL_CLOCK - 1_000L,
                purchaseToken = "tok-prior",
            ),
        )
        val (cache, updates, _, _, job) = newCache(storage = storage)
        runCurrent()
        // Sanity: we hydrated from the snapshot.
        assertThat(cache.state.value).isEqualTo(EntitlementState.Granted)

        // An empty Recovered (e.g. all owned purchases are already acked,
        // so they're filtered out) must NOT revoke. Same for a Recovered
        // carrying unrelated unacked purchases.
        updates.emit(OwnedPurchases.Recovered(emptyList()))
        runCurrent()
        assertThat(cache.state.value).isEqualTo(EntitlementState.Granted)

        updates.emit(OwnedPurchases.Recovered(listOf(fakePurchase(productId = "different-product"))))
        runCurrent()
        assertThat(cache.state.value).isEqualTo(EntitlementState.Granted)

        job.cancelAndJoin()
    }

    @Test
    fun `PurchaseRevoked matching the cached purchaseToken transitions to Revoked`() = runTest {
        val (cache, updates, _, _, job) = newCache()
        // Establish Granted baseline.
        val purchase = fakePurchase(productId = premiumProductId, purchaseToken = "premium-tok")
        updates.emit(OwnedPurchases.Live(listOf(purchase)))
        runCurrent()
        assertThat(cache.state.value).isEqualTo(EntitlementState.Granted)

        // Consumer's RTDN→FCM pipeline pushes a revocation for our cached purchase.
        updates.emit(PurchaseRevoked(purchaseToken = "premium-tok", reason = RevocationReason.Refunded))
        runCurrent()

        assertThat(cache.state.value).isEqualTo(EntitlementState.Revoked)

        job.cancelAndJoin()
    }

    @Test
    fun `PurchaseRevoked for a different token does not affect cached state`() = runTest {
        val (cache, updates, _, _, job) = newCache()
        val purchase = fakePurchase(productId = premiumProductId, purchaseToken = "premium-tok")
        updates.emit(OwnedPurchases.Live(listOf(purchase)))
        runCurrent()
        assertThat(cache.state.value).isEqualTo(EntitlementState.Granted)

        // Revocation arrives for an unrelated purchase the consumer is also tracking.
        updates.emit(PurchaseRevoked(purchaseToken = "some-other-tok", reason = RevocationReason.Chargeback))
        runCurrent()

        assertThat(cache.state.value).isEqualTo(EntitlementState.Granted)

        job.cancelAndJoin()
    }

    @Test
    fun `Failure with BillingUnavailable response code transitions to InGrace BillingUnavailable`() = runTest {
        val (cache, updates, _, clock, job) = newCache()
        // Establish Granted baseline so Failure can move us to InGrace.
        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = premiumProductId))))
        runCurrent()
        assertThat(cache.state.value).isEqualTo(EntitlementState.Granted)

        updates.emit(FlowOutcome.Failure(billingUnavailableException(), emptyList()))
        runCurrent()

        val state = cache.state.value
        assertThat(state).isInstanceOf(EntitlementState.InGrace::class.java)
        val grace = state as EntitlementState.InGrace
        assertThat(grace.reason).isEqualTo(GraceReason.BillingUnavailable)
        assertThat(grace.expiresAtMs).isEqualTo(clock() + DEFAULT_BILLING_UNAVAILABLE_MS)

        job.cancelAndJoin()
    }

    @Test
    fun `Failure with NetworkError response code transitions to InGrace TransientFailure`() = runTest {
        val (cache, updates, _, clock, job) = newCache()
        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = premiumProductId))))
        runCurrent()
        assertThat(cache.state.value).isEqualTo(EntitlementState.Granted)

        updates.emit(FlowOutcome.Failure(networkErrorException(), emptyList()))
        runCurrent()

        val state = cache.state.value
        assertThat(state).isInstanceOf(EntitlementState.InGrace::class.java)
        val grace = state as EntitlementState.InGrace
        assertThat(grace.reason).isEqualTo(GraceReason.TransientFailure)
        assertThat(grace.expiresAtMs).isEqualTo(clock() + DEFAULT_TRANSIENT_FAILURE_MS)

        job.cancelAndJoin()
    }

    @Test
    fun `InGrace transitions to Revoked when grace window expires on next emission`() = runTest {
        val mutableClock = MutableClock(INITIAL_CLOCK)
        val (cache, updates, _, _, job) = newCache(clock = mutableClock::value)
        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = premiumProductId))))
        runCurrent()
        updates.emit(FlowOutcome.Failure(networkErrorException(), emptyList()))
        runCurrent()
        val grace = cache.state.value as EntitlementState.InGrace

        // Advance the clock past the grace window. No tick fires inside this
        // runCurrent — the next reduce() call re-evaluates expiry on entry.
        mutableClock.advance(grace.expiresAtMs - mutableClock.value + 1L)
        // Any subsequent emission triggers re-evaluation; pick a benign one
        // that doesn't otherwise change state (Pending is a no-op).
        updates.emit(FlowOutcome.Pending(emptyList()))
        runCurrent()

        assertThat(cache.state.value).isEqualTo(EntitlementState.Revoked)
        job.cancelAndJoin()
    }

    @Test
    fun `InGrace transitions to Revoked via grace tick without any further upstream emission`() = runTest {
        val mutableClock = MutableClock(INITIAL_CLOCK)
        val tickInterval = 50L
        val (cache, updates, _, _, job) = newCache(
            clock = mutableClock::value,
            graceTickIntervalMs = tickInterval,
        )
        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = premiumProductId))))
        runCurrent()
        updates.emit(FlowOutcome.Failure(networkErrorException(), emptyList()))
        runCurrent()
        val grace = cache.state.value as EntitlementState.InGrace

        // Advance the virtual time + the clock past the grace window.
        // Use the test scheduler's advanceTimeBy to fire the tick coroutine,
        // and synchronise our own clock to match.
        val advanceBy = grace.expiresAtMs - mutableClock.value + tickInterval
        mutableClock.advance(advanceBy)
        testScheduler.advanceTimeBy(advanceBy + 1L)
        runCurrent()

        assertThat(cache.state.value).isEqualTo(EntitlementState.Revoked)
        job.cancelAndJoin()
    }

    @Test
    fun `Granted snapshot persists across cache instances via storage`() = runTest {
        val storage = FakeEntitlementStorage()

        // First instance: confirm a Live event and let it write through.
        val firstUpdates = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
        val firstCache = EntitlementCache(
            purchasesUpdates = firstUpdates,
            storage = storage,
            gracePolicy = defaultPolicy(),
            productPredicate = premiumPredicate,
            clock = { INITIAL_CLOCK },
            graceTickIntervalMs = 60_000L,
        )
        val firstJob = firstCache.start(this)
        runCurrent()
        firstUpdates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = premiumProductId, purchaseToken = "tok-rt"))))
        runCurrent()
        assertThat(firstCache.state.value).isEqualTo(EntitlementState.Granted)
        firstJob.cancelAndJoin()

        // Second instance with the same storage hydrates as Granted.
        val secondUpdates = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
        val secondCache = EntitlementCache(
            purchasesUpdates = secondUpdates,
            storage = storage,
            gracePolicy = defaultPolicy(),
            productPredicate = premiumPredicate,
            clock = { INITIAL_CLOCK + 1_000L },
            graceTickIntervalMs = 60_000L,
        )
        val secondJob = secondCache.start(this)
        runCurrent()
        assertThat(secondCache.state.value).isEqualTo(EntitlementState.Granted)
        // The persisted snapshot's purchaseToken survived the round trip.
        assertThat(storage.lastWritten?.purchaseToken).isEqualTo("tok-rt")
        secondJob.cancelAndJoin()
    }

    @Test
    fun `start called twice returns the same active Job`() = runTest {
        val updates = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
        val cache = EntitlementCache(
            purchasesUpdates = updates,
            storage = FakeEntitlementStorage(),
            gracePolicy = defaultPolicy(),
            productPredicate = premiumPredicate,
            clock = { INITIAL_CLOCK },
            graceTickIntervalMs = 60_000L,
        )
        val first = cache.start(this)
        val second = cache.start(this)
        // Both calls hand back the same active Job. Cancelling either stops
        // the cache; there's only one collector + tick.
        assertThat(second).isSameInstanceAs(first)
        assertThat(first.isActive).isTrue()
        first.cancelAndJoin()
    }

    @Test
    fun `start is restartable after the previous Job is cancelled`() = runTest {
        // Verifies the cache doesn't enter a permanently-dead state when a
        // start()'s Job (or scope) is cancelled — a second start() retries
        // hydration + launches a fresh collector.
        val updates = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
        val cache = EntitlementCache(
            purchasesUpdates = updates,
            storage = FakeEntitlementStorage(),
            gracePolicy = defaultPolicy(),
            productPredicate = premiumPredicate,
            clock = { INITIAL_CLOCK },
            graceTickIntervalMs = 60_000L,
        )
        val first = cache.start(this)
        first.cancelAndJoin()
        assertThat(first.isActive).isFalse()

        // Fresh start should establish a new active Job.
        val second = cache.start(this)
        runCurrent()
        assertThat(second).isNotSameInstanceAs(first)
        assertThat(second.isActive).isTrue()

        // Sanity: the new collector actually processes events.
        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = premiumProductId, purchaseToken = "after-restart"))))
        runCurrent()
        assertThat(cache.state.value).isEqualTo(EntitlementState.Granted)

        second.cancelAndJoin()
    }

    @Test
    fun `Failure while Revoked stays Revoked - no spurious InGrace`() = runTest {
        val (cache, updates, _, _, job) = newCache()
        // Default state is Revoked; no prior Granted observation.

        updates.emit(FlowOutcome.Failure(networkErrorException(), emptyList()))
        runCurrent()

        assertThat(cache.state.value).isEqualTo(EntitlementState.Revoked)
        job.cancelAndJoin()
    }

    @Test
    fun `Live of an unrelated product does not revoke an existing Granted state`() = runTest {
        val (cache, updates, _, _, job) = newCache()
        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = premiumProductId))))
        runCurrent()
        assertThat(cache.state.value).isEqualTo(EntitlementState.Granted)

        updates.emit(OwnedPurchases.Live(listOf(fakePurchase(productId = "coins_pack_50"))))
        runCurrent()

        // Live of a non-premium IAP does not negate entitlement; the cache
        // treats Live and Recovered as grant-only signals and routes
        // revocation through PurchaseRevoked + grace expiry instead.
        assertThat(cache.state.value).isEqualTo(EntitlementState.Granted)
        job.cancelAndJoin()
    }

    // -- Test helpers --------------------------------------------------------

    private data class CacheUnderTest(
        val cache: EntitlementCache,
        val updates: MutableSharedFlow<PurchaseEvent>,
        val storage: FakeEntitlementStorage,
        val clock: () -> Long,
        val job: Job,
    )

    private suspend fun TestScope.newCache(
        storage: FakeEntitlementStorage = FakeEntitlementStorage(),
        clock: () -> Long = { INITIAL_CLOCK },
        graceTickIntervalMs: Long = 60_000L,
    ): CacheUnderTest {
        val updates = MutableSharedFlow<PurchaseEvent>(extraBufferCapacity = 16)
        val cache = EntitlementCache(
            purchasesUpdates = updates,
            storage = storage,
            gracePolicy = defaultPolicy(),
            productPredicate = premiumPredicate,
            clock = clock,
            graceTickIntervalMs = graceTickIntervalMs,
        )
        val job = cache.start(this)
        runCurrent()
        return CacheUnderTest(cache, updates, storage, clock, job)
    }

    private fun defaultPolicy() = GracePolicy(
        billingUnavailableMs = DEFAULT_BILLING_UNAVAILABLE_MS,
        transientFailureMs = DEFAULT_TRANSIENT_FAILURE_MS,
    )

    private fun networkErrorException(): BillingException =
        BillingException.NetworkErrorException(
            BillingResult.newBuilder().setResponseCode(BillingResponseCode.NETWORK_ERROR).build(),
        )

    private fun billingUnavailableException(): BillingException =
        BillingException.BillingUnavailableException(
            BillingResult.newBuilder().setResponseCode(BillingResponseCode.BILLING_UNAVAILABLE).build(),
        )

    companion object {
        private const val INITIAL_CLOCK = 1_000_000L
        private const val DEFAULT_BILLING_UNAVAILABLE_MS = 60_000L
        private const val DEFAULT_TRANSIENT_FAILURE_MS = 10_000L
    }
}

private class FakeEntitlementStorage(
    initial: EntitlementSnapshot? = null,
) : EntitlementStorage {
    var lastWritten: EntitlementSnapshot? = initial

    override suspend fun read(): EntitlementSnapshot? = lastWritten

    override suspend fun write(snapshot: EntitlementSnapshot) {
        lastWritten = snapshot
    }
}

private class MutableClock(initial: Long) {
    var value: Long = initial
        private set

    fun advance(delta: Long) {
        value += delta
    }
}
