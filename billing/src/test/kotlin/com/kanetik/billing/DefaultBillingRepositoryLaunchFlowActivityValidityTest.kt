package com.kanetik.billing

import android.app.Activity
import com.android.billingclient.api.BillingFlowParams
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.exception.BillingException
import com.kanetik.billing.logging.BillingLogger
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DefaultBillingRepositoryLaunchFlowActivityValidityTest {

    @Test
    fun `launchFlow against a finishing activity throws DeveloperErrorException and logs once at error`() = runTest {
        val captor = CapturingLogger()
        val repo = DefaultBillingRepository(
            billingClientStorage = mockk(relaxed = true),
            logger = captor
        )
        val activity = mockk<Activity>(relaxed = true) {
            io.mockk.every { isFinishing } returns true
            io.mockk.every { isDestroyed } returns false
        }
        val params = mockk<BillingFlowParams>(relaxed = true)

        val thrown = runCatching { repo.launchFlow(activity, params) }.exceptionOrNull()

        assertThat(thrown).isInstanceOf(BillingException.DeveloperErrorException::class.java)
        assertThat(captor.warnings).isEmpty()
        assertThat(captor.errors).hasSize(1)
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
