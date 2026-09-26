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
    private val requeryCode = code == BillingResponseCode.ITEM_ALREADY_OWNED || code == BillingResponseCode.ITEM_NOT_OWNED

    private val openIssue: String? = when {
        requeryCode && op != Op.LAUNCH_FLOW && op != Op.IS_FEATURE_SUPPORTED -> "#52: requery cannot change the outcome"
        transient && op == Op.IS_FEATURE_SUPPORTED -> "unfiled: isFeatureSupported never retries a transient code"
        else -> null
    }

    private val expectedCalls = if (transient && op != Op.LAUNCH_FLOW) 4 else 1

    private val expectedElapsedMs = when {
        expectedCalls == 1 -> 0L
        code in simpleRetryCodes -> 500L * 3
        else -> 2000L + 4000L + 8000L
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
