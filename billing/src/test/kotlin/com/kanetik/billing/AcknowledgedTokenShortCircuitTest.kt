@file:OptIn(ExperimentalCoroutinesApi::class)

package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

internal class AcknowledgedTokenShortCircuitTest {

    @Test
    fun `re-handling a stale unacknowledged copy of a purchase this repository acknowledged returns AlreadyAcknowledged without calling Play`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(Op.ACKNOWLEDGE, BillingResponseCode.OK, BillingResponseCode.DEVELOPER_ERROR)
        }
        val repository = repositoryOver(play)
        val stale = fakePurchase(purchaseToken = "token-1", isAcknowledged = false)

        assertThat(repository.handlePurchase(stale, consume = false)).isEqualTo(HandlePurchaseResult.Success)
        assertThat(repository.handlePurchase(stale, consume = false)).isEqualTo(HandlePurchaseResult.AlreadyAcknowledged)
        assertThat(play.calls(Op.ACKNOWLEDGE)).isEqualTo(1)
    }

    @Test
    fun `a consume of an already-consumed token still goes to Play`() = runTest {
        val play = FakePlay().apply {
            script(Op.IS_FEATURE_SUPPORTED, BillingResponseCode.FEATURE_NOT_SUPPORTED)
            script(Op.CONSUME, BillingResponseCode.OK, BillingResponseCode.ITEM_NOT_OWNED)
        }
        val repository = repositoryOver(play)
        val stale = fakePurchase(purchaseToken = "token", isAcknowledged = false)

        assertThat(repository.handlePurchase(stale, consume = true)).isEqualTo(HandlePurchaseResult.Success)
        assertThat(repository.handlePurchase(stale, consume = true)).isEqualTo(HandlePurchaseResult.NotOwned)
        assertThat(play.calls(Op.CONSUME)).isEqualTo(2)
    }
}
