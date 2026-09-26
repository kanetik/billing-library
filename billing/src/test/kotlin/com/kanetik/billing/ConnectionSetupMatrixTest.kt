package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
class ConnectionSetupMatrixTest(private val codeName: String) {
    private val code = codeNames.entries.single { it.value == codeName }.key
    private val transient = code in simpleRetryCodes || code in exponentialRetryCodes
    private val expectedStarts = if (transient) ConnectionRetryPolicy.DEFAULT_MAX_ATTEMPTS else 1
    private val expectedElapsedMs = when {
        !transient -> 0L
        code in simpleRetryCodes -> 500L * 3
        else -> 2000L + 4000L + 8000L
    }

    private fun FakePlay.connectingWith(code: Int) = apply { repeat(8) { connectCodes.addLast(code) } }

    @Test
    fun `retries startConnection only for transient codes`() = runTest {
        val play = FakePlay().connectingWith(code)
        val repo = repositoryOver(play)

        val outcome = timed { repo.perform(Op.QUERY_PURCHASES) }

        assertThat(play.startConnectionCount).isEqualTo(expectedStarts)
        assertThat(outcome.elapsedMs).isEqualTo(expectedElapsedMs)
    }

    @Test
    fun `an operation surfaces the connect failure without calling Play`() = runTest {
        val play = FakePlay().connectingWith(code)
        val repo = repositoryOver(play)

        val result = runCatching { repo.perform(Op.QUERY_PURCHASES) }

        if (code == BillingResponseCode.OK) {
            assertThat(result.isSuccess).isTrue()
            assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(1)
        } else {
            assertThat(result.exceptionOrNull())
                .isInstanceOf(BillingException.fromResult(billingResult(code))::class.java)
            assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(0)
        }
    }

    @Test
    fun `connectToBilling reports the connect outcome`() = runTest {
        val play = FakePlay().connectingWith(code)
        val repo = repositoryOver(play)

        val result = repo.connectToBilling().first()

        if (code == BillingResponseCode.OK) {
            assertThat(result).isEqualTo(BillingConnectionResult.Success)
        } else {
            assertThat((result as BillingConnectionResult.Error).exception)
                .isInstanceOf(BillingException.fromResult(billingResult(code))::class.java)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Array<Any>> = codeNames.values.map { arrayOf<Any>(it) }
    }
}
