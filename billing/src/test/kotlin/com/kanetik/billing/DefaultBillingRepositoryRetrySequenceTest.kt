package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
internal class DefaultBillingRepositoryRetrySequenceTest(
    private val op: Op,
    private val transientName: String
) {
    private val transient = codeNames.entries.single { it.value == transientName }.key
    private val interactive = op == Op.QUERY_PRODUCT_DETAILS
    private val backoffMs = if (interactive) 500L else 2000L
    private val maxAttempts = if (interactive) 3 else 5

    @Test
    fun `transient then OK makes exactly two calls and succeeds after one backoff`() = runTest {
        val play = FakePlay().apply { script(op, transient, BillingResponseCode.OK) }
        val repo = repositoryOver(play)

        val outcome = timed { repo.perform(op) }

        assertThat(outcome.result.exceptionOrNull()).isNull()
        assertThat(play.calls(op)).isEqualTo(2)
        assertThat(outcome.elapsedMs).isEqualTo(backoffMs)
    }

    @Test
    fun `transient then USER_CANCELED does not retry the cancel`() = runTest {
        val play = FakePlay().apply { script(op, transient, BillingResponseCode.USER_CANCELED, BillingResponseCode.OK) }
        val repo = repositoryOver(play)

        val outcome = timed { repo.perform(op) }

        assertThat(outcome.result.exceptionOrNull())
            .isInstanceOf(BillingException.UserCanceledException::class.java)
        assertThat(play.calls(op)).isEqualTo(2)
    }

    @Test
    fun `transient then OK on the final attempt succeeds`() = runTest {
        val codes = List(maxAttempts - 1) { transient } + BillingResponseCode.OK
        val play = FakePlay().apply { script(op, *codes.toIntArray()) }
        val repo = repositoryOver(play)

        val outcome = timed { repo.perform(op) }

        assertThat(outcome.result.exceptionOrNull()).isNull()
        assertThat(play.calls(op)).isEqualTo(maxAttempts)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun cases(): List<Array<Any>> =
            listOf(Op.QUERY_PRODUCT_DETAILS, Op.QUERY_PURCHASES, Op.ACKNOWLEDGE, Op.CONSUME).flatMap { op ->
                listOf("SERVICE_DISCONNECTED", "SERVICE_UNAVAILABLE", "NETWORK_ERROR", "ERROR")
                    .map { arrayOf<Any>(op, it) }
            }
    }
}
