package com.kanetik.billing.ext

import android.app.Activity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.google.common.truth.Truth.assertThat
import io.mockk.every
import io.mockk.mockk
import org.junit.Test

class ActivityValidatorTest {

    @Test
    fun `returns false when activity is finishing`() {
        val activity = mockk<Activity>(relaxed = true)
        every { activity.isFinishing } returns true
        every { activity.isDestroyed } returns false
        assertThat(validatePurchaseActivity(activity)).isFalse()
    }

    @Test
    fun `returns false when activity is destroyed`() {
        val activity = mockk<Activity>(relaxed = true)
        every { activity.isFinishing } returns false
        every { activity.isDestroyed } returns true
        assertThat(validatePurchaseActivity(activity)).isFalse()
    }

    @Test
    fun `returns true for non-LifecycleOwner activity that is alive`() {
        val activity = mockk<Activity>(relaxed = true)
        every { activity.isFinishing } returns false
        every { activity.isDestroyed } returns false
        assertThat(validatePurchaseActivity(activity)).isTrue()
    }

    @Test
    fun `returns false when LifecycleOwner activity is INITIALIZED`() {
        assertThat(validatePurchaseActivity(lifecycleActivity(Lifecycle.State.INITIALIZED))).isFalse()
    }

    @Test
    fun `returns false when LifecycleOwner activity is CREATED`() {
        assertThat(validatePurchaseActivity(lifecycleActivity(Lifecycle.State.CREATED))).isFalse()
    }

    @Test
    fun `returns false when LifecycleOwner activity is STARTED but not RESUMED`() {
        // Per J2 in the architectural review: we use RESUMED, not STARTED.
        assertThat(validatePurchaseActivity(lifecycleActivity(Lifecycle.State.STARTED))).isFalse()
    }

    @Test
    fun `returns true when LifecycleOwner activity is RESUMED`() {
        assertThat(validatePurchaseActivity(lifecycleActivity(Lifecycle.State.RESUMED))).isTrue()
    }

    @Test
    fun `returns false when LifecycleOwner activity is DESTROYED via lifecycle state`() {
        assertThat(validatePurchaseActivity(lifecycleActivity(Lifecycle.State.DESTROYED))).isFalse()
    }

    /**
     * Builds an Activity mock that's also a LifecycleOwner with the given current state.
     * Mocks Lifecycle directly rather than using LifecycleRegistry — Registry calls
     * Looper.getMainLooper() which is stubbed in pure-JVM unit tests.
     */
    private fun lifecycleActivity(state: Lifecycle.State): Activity {
        val lifecycle = mockk<Lifecycle>(relaxed = true).also {
            every { it.currentState } returns state
        }
        return mockk<ActivityLifecycle>(relaxed = true).also {
            every { it.isFinishing } returns false
            every { it.isDestroyed } returns (state == Lifecycle.State.DESTROYED)
            every { it.lifecycle } returns lifecycle
        }
    }

    /** Marker class combining Activity + LifecycleOwner so `is LifecycleOwner` checks pass. */
    abstract class ActivityLifecycle : Activity(), LifecycleOwner
}
