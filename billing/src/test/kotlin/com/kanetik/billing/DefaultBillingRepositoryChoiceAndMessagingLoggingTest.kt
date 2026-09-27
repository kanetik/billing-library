@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalBillingChoiceApi::class)

package com.kanetik.billing

import android.app.Activity
import com.android.billingclient.api.BillingChoiceInfoResponseListener
import com.android.billingclient.api.BillingClient.BillingResponseCode
import com.android.billingclient.api.BillingProgramInformationDialogListener
import com.android.billingclient.api.BillingProgramInformationDialogParams
import com.android.billingclient.api.GetBillingChoiceInfoParams
import com.android.billingclient.api.InAppMessageParams
import com.android.billingclient.api.InAppMessageResponseListener
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.choice.ExperimentalBillingChoiceApi
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultBillingRepositoryChoiceAndMessagingLoggingTest {

    private class Fixture(scope: TestScope) {
        val play = FakePlay()
        val captor = CapturingLogger()
        val repo = DefaultBillingRepository(
            billingClientStorage = scope.storageOver(play),
            logger = captor,
            ioDispatcher = UnconfinedTestDispatcher(scope.testScheduler),
            uiDispatcher = UnconfinedTestDispatcher(scope.testScheduler)
        )
    }

    private suspend fun Fixture.connectedClient() = run {
        // Any scripted op forces the connection so `play.clients` holds the mock
        // this test then stubs directly for a PBL call FakePlay doesn't script.
        repo.perform(Op.IS_FEATURE_SUPPORTED)
        play.clients.single()
    }

    @Test
    fun `showInAppMessages failure is logged exactly once`() = runTest {
        val f = Fixture(this)
        val client = f.connectedClient()
        every {
            client.showInAppMessages(any<Activity>(), any<InAppMessageParams>(), any<InAppMessageResponseListener>())
        } returns billingResult(BillingResponseCode.DEVELOPER_ERROR)

        val outcome = runCatching { f.repo.showInAppMessages(mockk(relaxed = true), mockk(relaxed = true)) }

        assertFailureAndSingleErrorLog(outcome, f.captor)
    }

    @Test
    fun `getBillingChoiceInfo failure is logged exactly once`() = runTest {
        val f = Fixture(this)
        val client = f.connectedClient()
        every {
            client.getBillingChoiceInfoAsync(any<GetBillingChoiceInfoParams>(), any())
        } answers {
            secondArg<BillingChoiceInfoResponseListener>()
                .onBillingChoiceInfoResponse(billingResult(BillingResponseCode.DEVELOPER_ERROR), mockk(relaxed = true))
        }

        val outcome = runCatching { f.repo.getBillingChoiceInfo(mockk(relaxed = true)) }

        assertFailureAndSingleErrorLog(outcome, f.captor)
    }

    @Test
    fun `showBillingProgramInformationDialog failure is logged exactly once`() = runTest {
        val f = Fixture(this)
        val client = f.connectedClient()
        every {
            client.showBillingProgramInformationDialog(
                any<Activity>(),
                any<BillingProgramInformationDialogParams>(),
                any()
            )
        } answers {
            thirdArg<BillingProgramInformationDialogListener>()
                .onBillingProgramInformationDialogResponse(billingResult(BillingResponseCode.DEVELOPER_ERROR))
        }

        val outcome = runCatching { f.repo.showBillingProgramInformationDialog(mockk(relaxed = true), mockk(relaxed = true)) }

        assertFailureAndSingleErrorLog(outcome, f.captor)
    }

    private fun assertFailureAndSingleErrorLog(outcome: Result<*>, captor: CapturingLogger) {
        assertThat(outcome.exceptionOrNull()).isInstanceOf(BillingException.DeveloperErrorException::class.java)
        assertThat(captor.errors).hasSize(1)
        assertThat(captor.warnings).isEmpty()
    }

    private class CapturingLogger : BillingLogger {
        val warnings = mutableListOf<String>()
        val errors = mutableListOf<String>()
        override fun d(message: String, throwable: Throwable?) = Unit
        override fun w(message: String, throwable: Throwable?) {
            warnings += message
        }
        override fun e(message: String, throwable: Throwable?) {
            errors += message
        }
    }
}
