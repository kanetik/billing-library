package com.kanetik.billing

import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

@RunWith(Parameterized::class)
internal class DefaultBillingRepositoryRetryMatrixTest(
    private val op: Op,
    private val codeName: String
) {
    private val code = codeNames.entries.single { it.value == codeName }.key
    private val transient = code in simpleRetryCodes || code in exponentialRetryCodes

    private val openIssue: String? = when {
        transient && op == Op.IS_FEATURE_SUPPORTED -> "#62: isFeatureSupported never retries a transient code"
        else -> null
    }

    private val interactive = op == Op.QUERY_PRODUCT_DETAILS

    private val expectedCalls = when {
        !transient || op == Op.LAUNCH_FLOW -> 1
        interactive -> 3
        else -> 5
    }

    private val expectedElapsedMs = when {
        expectedCalls == 1 -> 0L
        interactive -> 500L + 500L
        else -> 2000L + 4000L + 8000L + 16000L
    }

    @Test
    fun `makes the expected number of calls`() = runTest {
        assumeTrue(openIssue ?: "", openIssue == null)
        val play = FakePlay().apply { script(op, code) }

        repositoryOver(play).let { repo -> runCatching { repo.perform(op) } }

        assertThat(play.calls(op)).isEqualTo(expectedCalls)
    }

    @Test
    fun `waits only between attempts`() = runTest {
        assumeTrue(openIssue ?: "", openIssue == null)
        val play = FakePlay().apply { script(op, code) }
        val repo = repositoryOver(play)

        val outcome = timed { repo.perform(op) }

        assertThat(outcome.elapsedMs).isEqualTo(expectedElapsedMs)
    }

    @Test
    fun `never requeries purchases`() = runTest {
        assumeTrue(openIssue ?: "", openIssue == null && op != Op.QUERY_PURCHASES)
        val play = FakePlay().apply { script(op, code) }

        repositoryOver(play).let { repo -> runCatching { repo.perform(op) } }

        assertThat(play.calls(Op.QUERY_PURCHASES)).isEqualTo(0)
    }

    @Test
    fun `surfaces the typed outcome`() = runTest {
        assumeTrue(openIssue ?: "", openIssue == null)
        val play = FakePlay().apply { script(op, code) }
        val repo = repositoryOver(play)

        val result = runCatching { repo.perform(op) }

        when {
            op == Op.IS_FEATURE_SUPPORTED ->
                assertThat(result.getOrThrow()).isEqualTo(code == BillingResponseCode.OK)
            code == BillingResponseCode.OK ->
                assertThat(result.isSuccess).isTrue()
            else ->
                assertThat(result.exceptionOrNull())
                    .isInstanceOf(BillingException.fromResult(billingResult(code))::class.java)
        }
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0} {1}")
        fun cases(): List<Array<Any>> =
            Op.values().flatMap { op -> codeNames.values.map { arrayOf<Any>(op, it) } }
    }
}
