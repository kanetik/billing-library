package com.kanetik.billing

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ConnectionRetryPolicyTest {

    @Test
    fun `defaults mirror the operation-level retry loop`() {
        val policy = ConnectionRetryPolicy()
        assertThat(policy.maxAttempts).isEqualTo(4)
        assertThat(policy.simpleRetryBackoffMillis).isEqualTo(500L)
        assertThat(policy.exponentialBaseBackoffMillis).isEqualTo(2000L)
        assertThat(policy.exponentialBackoffFactor).isEqualTo(2)
    }

    @Test
    fun `None disables retry by capping attempts at one`() {
        assertThat(ConnectionRetryPolicy.None.maxAttempts).isEqualTo(1)
    }

    @Test
    fun `rejects maxAttempts below one`() {
        val error = runCatching { ConnectionRetryPolicy(maxAttempts = 0) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects negative simple backoff`() {
        val error = runCatching { ConnectionRetryPolicy(simpleRetryBackoffMillis = -1L) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects negative exponential base backoff`() {
        val error = runCatching { ConnectionRetryPolicy(exponentialBaseBackoffMillis = -1L) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `rejects exponential factor below one`() {
        val error = runCatching { ConnectionRetryPolicy(exponentialBackoffFactor = 0) }.exceptionOrNull()
        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }
}
