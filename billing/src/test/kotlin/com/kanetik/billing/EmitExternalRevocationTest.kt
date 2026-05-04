package com.kanetik.billing

import com.android.billingclient.api.PurchasesUpdatedListener
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.factory.BillingConnectionFactory
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * Tests for [BillingRepository.emitExternalRevocation] and the underlying
 * [BillingClientStorage.emitExternalRevocation] routing through the dedicated
 * revocation channel (replay = 16).
 *
 * The factory is stubbed with an empty connection flow — these tests do not
 * exercise the Play Billing connection or recovery sweep; they exercise the
 * synthetic-emit path end-to-end through the repository surface.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EmitExternalRevocationTest {

    // Holds child Jobs created by `shareIn` inside BillingClientStorage so we can
    // cancel them at test teardown — runTest fails if any coroutine is still
    // running when the test body returns, and the share-scope's shareIn launches
    // a long-lived job for the connection flow.
    private val shareScopes = mutableListOf<Job>()

    @Test
    fun `emitExternalRevocation produces a PurchaseRevoked event downstream`() = runTest {
        val repo = newRepository(this)

        try {
            // Subscribe before emitting so the live-attach path is exercised.
            val collected = mutableListOf<PurchaseEvent>()
            // Take a single emission — the suspending merge() upstream stays alive
            // forever, so we bound the collection to keep runTest's
            // "all coroutines completed" check happy.
            val collectorJob = launch {
                repo.observePurchaseUpdates().take(1).collect { collected += it }
            }
            // Yield via testScheduler so the collector attaches to the merged
            // SharedFlow before we emit. Without this the emission would race.
            testScheduler.advanceUntilIdle()

            repo.emitExternalRevocation(
                purchaseToken = "token-abc",
                reason = RevocationReason.Refunded,
            )
            collectorJob.join()

            assertThat(collected).hasSize(1)
            val event = collected.single()
            assertThat(event).isInstanceOf(PurchaseRevoked::class.java)
            val revoked = event as PurchaseRevoked
            assertThat(revoked.purchaseToken).isEqualTo("token-abc")
            assertThat(revoked.reason).isEqualTo(RevocationReason.Refunded)
        } finally {
            cancelShareScopes()
        }
    }

    @Test
    fun `PurchaseRevoked event survives a late subscriber via replay-cache plumbing`() = runTest {
        val repo = newRepository(this)

        try {
            // Emit BEFORE any subscriber attaches — exercises the replay = 16
            // channel semantic that is the whole point of routing PurchaseRevoked
            // through a dedicated replay channel rather than the live channel.
            repo.emitExternalRevocation(
                purchaseToken = "late-token",
                reason = RevocationReason.Chargeback,
            )

            // Subscribe afterward; first() should resolve to the replayed
            // PurchaseRevoked event without the test hanging.
            val first = repo.observePurchaseUpdates().first()
            assertThat(first).isInstanceOf(PurchaseRevoked::class.java)
            val revoked = first as PurchaseRevoked
            assertThat(revoked.purchaseToken).isEqualTo("late-token")
            assertThat(revoked.reason).isEqualTo(RevocationReason.Chargeback)
        } finally {
            cancelShareScopes()
        }
    }

    @Test
    fun `each RevocationReason value round-trips through the emit API unchanged`() = runTest {
        try {
            // Iterate every enum value so adding a new RevocationReason without
            // a corresponding round-trip lights up here. The emit/observe pair is
            // pass-through — the library does not interpret the reason — but the
            // test guards against accidental enum-erasure in the data class
            // definition or future serialization layer.
            for (reason in RevocationReason.entries) {
                val repo = newRepository(this)
                repo.emitExternalRevocation(purchaseToken = "tok-$reason", reason = reason)
                val event = repo.observePurchaseUpdates().first()
                assertThat(event).isInstanceOf(PurchaseRevoked::class.java)
                val revoked = event as PurchaseRevoked
                assertThat(revoked.reason).isEqualTo(reason)
                assertThat(revoked.purchaseToken).isEqualTo("tok-$reason")
            }
        } finally {
            cancelShareScopes()
        }
    }

    private fun cancelShareScopes() {
        shareScopes.forEach { it.cancel() }
        shareScopes.clear()
    }

    /**
     * Constructs a [DefaultBillingRepository] backed by a [BillingClientStorage]
     * with a stubbed connection factory that emits no states. The recovery
     * sweep never runs (no Connected state ever reaches it), which is exactly
     * what we want — these tests target the synthetic-emit path, not the
     * sweep path.
     *
     * Uses [UnconfinedTestDispatcher] so emit/collect interleave eagerly and
     * we don't have to micromanage the scheduler.
     */
    private fun newRepository(testScope: TestScope): BillingRepository {
        val factory = object : BillingConnectionFactory {
            override fun createBillingConnectionFlow(
                listener: PurchasesUpdatedListener
            ): Flow<InternalConnectionState> = emptyFlow()
        }
        val unconfined = UnconfinedTestDispatcher(testScope.testScheduler)
        // Separate Job for the share scope so its long-lived shareIn coroutines
        // can be cancelled in @After without tripping runTest's
        // "test coroutine is still running" check (children of the test scope
        // would be kept alive by the test body's scope).
        val shareJob = Job()
        shareScopes += shareJob
        val storage = BillingClientStorage(
            billingFactory = factory,
            logger = BillingLogger.Noop,
            connectionShareScope = CoroutineScope(testScope.coroutineContext + shareJob),
            ioDispatcher = unconfined,
            recoverPurchasesOnConnect = false,
        )
        return DefaultBillingRepository(
            billingClientStorage = storage,
            logger = BillingLogger.Noop,
            ioDispatcher = unconfined,
            uiDispatcher = unconfined,
        )
    }

}
