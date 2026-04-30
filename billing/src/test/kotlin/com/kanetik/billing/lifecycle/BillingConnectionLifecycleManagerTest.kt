package com.kanetik.billing.lifecycle

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.BillingConnectionResult
import com.kanetik.billing.BillingConnector
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BillingConnectionLifecycleManagerTest {

    @Before
    fun setUp() {
        // The manager's coroutineContext includes Dispatchers.Main; without
        // setMain the test environment can't resolve it.
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `onStart launches a collector that holds the connection upstream alive`() {
        // We can't directly observe the connection-warm side effect, but we can
        // verify that after onStart the manager's internal job has an active
        // child (the collector coroutine).
        val sharedFlow = MutableSharedFlow<BillingConnectionResult>(replay = 1).asSharedFlow()
        val connector = mockk<BillingConnector>().also {
            every { it.connectToBilling() } returns sharedFlow as kotlinx.coroutines.flow.SharedFlow<BillingConnectionResult>
        }
        val manager = BillingConnectionLifecycleManager(connector)
        manager.onStart(stubOwner())

        // The manager's coroutineContext job must be active and have at least one
        // child (the launched collect block).
        assertThat(manager.coroutineContext[kotlinx.coroutines.Job]?.isActive).isTrue()
        assertThat(manager.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()).isNotEmpty()
    }

    @Test
    fun `onStop cancels active children but keeps the job itself alive`() {
        val sharedFlow = MutableSharedFlow<BillingConnectionResult>(replay = 1).asSharedFlow()
        val connector = mockk<BillingConnector>().also {
            every { it.connectToBilling() } returns sharedFlow as kotlinx.coroutines.flow.SharedFlow<BillingConnectionResult>
        }
        val manager = BillingConnectionLifecycleManager(connector)
        val owner = stubOwner()

        manager.onStart(owner)
        manager.onStop(owner)

        val job = manager.coroutineContext[kotlinx.coroutines.Job]!!
        // Children cancelled — should be empty or all cancelled.
        assertThat(job.children.all { !it.isActive }).isTrue()
        // Parent job itself is still alive (only onDestroy cancels it).
        assertThat(job.isActive).isTrue()
    }

    @Test
    fun `onDestroy cancels the SupervisorJob entirely`() {
        val sharedFlow = MutableSharedFlow<BillingConnectionResult>(replay = 1).asSharedFlow()
        val connector = mockk<BillingConnector>().also {
            every { it.connectToBilling() } returns sharedFlow as kotlinx.coroutines.flow.SharedFlow<BillingConnectionResult>
        }
        val manager = BillingConnectionLifecycleManager(connector)
        val owner = stubOwner()

        manager.onStart(owner)
        manager.onDestroy(owner)

        val job = manager.coroutineContext[kotlinx.coroutines.Job]!!
        // Parent job is now cancelled — closes the captured CoroutineExceptionHandler
        // closure and any retained references.
        assertThat(job.isActive).isFalse()
    }

    private fun stubOwner(): LifecycleOwner = mockk<LifecycleOwner>(relaxed = true).also {
        every { it.lifecycle } returns mockk(relaxed = true) {
            every { currentState } returns Lifecycle.State.STARTED
        }
    }
}
