package com.kanetik.billing.entitlement.signed

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class ServerSeededKeyProviderTest {

    @Test
    fun `fetchSeed invoked exactly once across multiple sign calls`() = runTest {
        val callCount = AtomicInteger(0)
        val cache = InMemorySeedCache()
        val provider = ServerSeededKeyProvider(
            fetchSeed = {
                callCount.incrementAndGet()
                ByteArray(32) { 0x42 }
            },
            cache = cache,
        )

        provider.sign("first".toByteArray())
        provider.sign("second".toByteArray())
        provider.sign("third".toByteArray())

        assertThat(callCount.get()).isEqualTo(1)
    }

    @Test
    fun `existing cached seed prevents fetch from running`() = runTest {
        val callCount = AtomicInteger(0)
        val cache = InMemorySeedCache().also { it.cached = ByteArray(32) { 0x11 } }
        val provider = ServerSeededKeyProvider(
            fetchSeed = {
                callCount.incrementAndGet()
                ByteArray(32) { 0x42 }
            },
            cache = cache,
        )

        provider.sign("data".toByteArray())

        assertThat(callCount.get()).isEqualTo(0)
    }

    @Test
    fun `verify returns true for matching signature, false for tampered`() = runTest {
        val provider = ServerSeededKeyProvider(
            fetchSeed = { ByteArray(32) { it.toByte() } },
            cache = InMemorySeedCache(),
        )
        val data = "payload".toByteArray()
        val sig = provider.sign(data)

        assertThat(provider.verify(data, sig)).isTrue()

        val tampered = sig.copyOf().also { it[it.size - 1] = (it[it.size - 1].toInt() xor 0x01).toByte() }
        assertThat(provider.verify(data, tampered)).isFalse()
    }

    @Test
    fun `different seeds produce different signatures for the same input`() = runTest {
        val data = "payload".toByteArray()

        val seedA = ServerSeededKeyProvider(
            fetchSeed = { ByteArray(32) { 0x01 } },
            cache = InMemorySeedCache(),
        )
        val seedB = ServerSeededKeyProvider(
            fetchSeed = { ByteArray(32) { 0x02 } },
            cache = InMemorySeedCache(),
        )

        assertThat(seedA.sign(data)).isNotEqualTo(seedB.sign(data))
    }

    @Test
    fun `mutating bytes returned by fetchSeed does not affect cached key`() = runTest {
        val mutableSeed = ByteArray(32) { 0x33 }
        val provider = ServerSeededKeyProvider(
            fetchSeed = { mutableSeed },
            cache = InMemorySeedCache(),
        )
        val data = "payload".toByteArray()
        val sigBefore = provider.sign(data)

        // Caller mutates the seed they handed us. We should have copied it.
        mutableSeed.fill(0x00)

        val sigAfter = provider.sign(data)
        assertThat(sigAfter).isEqualTo(sigBefore)
    }

    @Test
    fun `mutating cache contents after sign does not affect cached key`() = runTest {
        val cache = InMemorySeedCache().also { it.cached = ByteArray(32) { 0x44 } }
        val provider = ServerSeededKeyProvider(
            fetchSeed = { error("should not fetch") },
            cache = cache,
        )
        val data = "payload".toByteArray()
        val sigBefore = provider.sign(data)

        cache.cached!!.fill(0x00)

        val sigAfter = provider.sign(data)
        assertThat(sigAfter).isEqualTo(sigBefore)
    }

    @Test
    fun `fetched seed is persisted to cache for subsequent sessions`() = runTest {
        val cache = InMemorySeedCache()
        val provider = ServerSeededKeyProvider(
            fetchSeed = { ByteArray(32) { 0x55 } },
            cache = cache,
        )

        provider.sign("data".toByteArray())

        assertThat(cache.cached).isEqualTo(ByteArray(32) { 0x55 })
    }
}

private class InMemorySeedCache : SeedCache {
    var cached: ByteArray? = null

    override suspend fun read(): ByteArray? = cached

    override suspend fun write(seed: ByteArray) {
        cached = seed
    }
}
