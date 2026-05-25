package com.kanetik.billing.entitlement.signed

import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.entitlement.EntitlementSnapshot
import com.kanetik.billing.entitlement.EntitlementStorage
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.nio.ByteBuffer
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class SignedEntitlementStorageTest {

    private val keyOne = "entitlement_one"
    private val keyTwo = "entitlement_two"

    private val sampleSnapshotOne = EntitlementSnapshot(
        isEntitled = true,
        confirmedAtMs = 1_700_000_000_000L,
        purchaseToken = "tok-1",
    )
    private val sampleSnapshotTwo = EntitlementSnapshot(
        isEntitled = true,
        confirmedAtMs = 1_700_000_005_000L,
        purchaseToken = "tok-2",
    )

    @Test
    fun `write then readAll roundtrips the same snapshot for one key`() = runTest {
        val (storage, delegate, sigStore) = newStorage()

        storage.write(keyOne, sampleSnapshotOne)

        assertThat(delegate.last(keyOne)).isEqualTo(sampleSnapshotOne)
        assertThat(sigStore.lastFor(keyOne)).isNotNull()
        assertThat(storage.readAll()).containsExactly(keyOne, sampleSnapshotOne)
    }

    @Test
    fun `write then readAll roundtrips snapshots for multiple keys`() = runTest {
        val (storage, _, _) = newStorage()
        storage.write(keyOne, sampleSnapshotOne)
        storage.write(keyTwo, sampleSnapshotTwo)

        assertThat(storage.readAll())
            .containsExactly(keyOne, sampleSnapshotOne, keyTwo, sampleSnapshotTwo)
    }

    @Test
    fun `readAll returns empty without firing callback when delegate has no snapshots`() = runTest {
        val tamperEvents = mutableListOf<Pair<String, TamperEvent>>()
        val (storage, _, _) = newStorage(onTamperDetected = { key, e -> tamperEvents += key to e })

        assertThat(storage.readAll()).isEmpty()
        assertThat(tamperEvents).isEmpty()
    }

    @Test
    fun `readAll fires MissingSignature when snapshot present but signature missing`() = runTest {
        val tamperEvents = mutableListOf<Pair<String, TamperEvent>>()
        val (storage, delegate, _) = newStorage(onTamperDetected = { key, e -> tamperEvents += key to e })

        delegate.put(keyOne, sampleSnapshotOne)

        assertThat(storage.readAll()).isEmpty()
        assertThat(tamperEvents).containsExactly(keyOne to TamperEvent.MissingSignature)
    }

    @Test
    fun `readAll fires InvalidSignature when snapshot mutated after signing`() = runTest {
        val tamperEvents = mutableListOf<Pair<String, TamperEvent>>()
        val (storage, delegate, _) = newStorage(onTamperDetected = { key, e -> tamperEvents += key to e })

        storage.write(keyOne, sampleSnapshotOne)
        delegate.put(keyOne, sampleSnapshotOne.copy(isEntitled = false))

        assertThat(storage.readAll()).isEmpty()
        assertThat(tamperEvents).containsExactly(keyOne to TamperEvent.InvalidSignature)
    }

    @Test
    fun `readAll fires InvalidSignature when signature byte mutated`() = runTest {
        val tamperEvents = mutableListOf<Pair<String, TamperEvent>>()
        val (storage, _, sigStore) = newStorage(onTamperDetected = { key, e -> tamperEvents += key to e })

        storage.write(keyOne, sampleSnapshotOne)
        val original = sigStore.lastFor(keyOne)!!
        val mutated = original.copyOf()
        mutated[mutated.size - 1] = (mutated[mutated.size - 1].toInt() xor 0xff).toByte()
        sigStore.put(keyOne, mutated)

        assertThat(storage.readAll()).isEmpty()
        assertThat(tamperEvents).containsExactly(keyOne to TamperEvent.InvalidSignature)
    }

    @Test
    fun `cross-key signature swap fails verification`() = runTest {
        // The strong guarantee from including the entitlement key in v2
        // canonical bytes: a sig for keyOne can't validate a snapshot stored
        // under keyTwo (and vice versa) even when both pairs share the same
        // underlying snapshot fields.
        val tamperEvents = mutableListOf<Pair<String, TamperEvent>>()
        val (storage, _, sigStore) = newStorage(onTamperDetected = { key, e -> tamperEvents += key to e })

        // Identical snapshot bytes under both keys.
        storage.write(keyOne, sampleSnapshotOne)
        storage.write(keyTwo, sampleSnapshotOne)

        // Swap the signature blobs.
        val sigOne = sigStore.lastFor(keyOne)!!
        val sigTwo = sigStore.lastFor(keyTwo)!!
        sigStore.put(keyOne, sigTwo)
        sigStore.put(keyTwo, sigOne)

        assertThat(storage.readAll()).isEmpty()
        assertThat(tamperEvents).containsExactly(
            keyOne to TamperEvent.InvalidSignature,
            keyTwo to TamperEvent.InvalidSignature,
        )
    }

    @Test
    fun `readAll fires UnsupportedVersion when blob declares version above MAX_SUPPORTED_VERSION`() = runTest {
        val tamperEvents = mutableListOf<Pair<String, TamperEvent>>()
        val (storage, _, sigStore) = newStorage(onTamperDetected = { key, e -> tamperEvents += key to e })

        storage.write(keyOne, sampleSnapshotOne)
        val originalBlob = sigStore.lastFor(keyOne)!!
        val futureBlob = ByteBuffer.allocate(36).apply {
            putInt(SnapshotCanonicalBytes.MAX_SUPPORTED_VERSION + 1)
            put(originalBlob.copyOfRange(4, 36))
        }.array()
        sigStore.put(keyOne, futureBlob)

        assertThat(storage.readAll()).isEmpty()
        assertThat(tamperEvents).containsExactly(
            keyOne to TamperEvent.UnsupportedVersion(SnapshotCanonicalBytes.MAX_SUPPORTED_VERSION + 1),
        )
    }

    @Test
    fun `readAll fires UnsupportedVersion when blob declares version 0`() = runTest {
        val tamperEvents = mutableListOf<Pair<String, TamperEvent>>()
        val (storage, _, sigStore) = newStorage(onTamperDetected = { key, e -> tamperEvents += key to e })

        storage.write(keyOne, sampleSnapshotOne)
        val zeroBlob = ByteBuffer.allocate(36).apply {
            putInt(0)
            put(sigStore.lastFor(keyOne)!!.copyOfRange(4, 36))
        }.array()
        sigStore.put(keyOne, zeroBlob)

        assertThat(storage.readAll()).isEmpty()
        assertThat(tamperEvents).containsExactly(keyOne to TamperEvent.UnsupportedVersion(0))
    }

    @Test
    fun `readAll fires UnsupportedVersion when blob declares negative version`() = runTest {
        val tamperEvents = mutableListOf<Pair<String, TamperEvent>>()
        val (storage, _, sigStore) = newStorage(onTamperDetected = { key, e -> tamperEvents += key to e })

        storage.write(keyOne, sampleSnapshotOne)
        val negativeBlob = ByteBuffer.allocate(36).apply {
            putInt(-1)
            put(sigStore.lastFor(keyOne)!!.copyOfRange(4, 36))
        }.array()
        sigStore.put(keyOne, negativeBlob)

        assertThat(storage.readAll()).isEmpty()
        assertThat(tamperEvents).containsExactly(keyOne to TamperEvent.UnsupportedVersion(-1))
    }

    @Test
    fun `readAll fires InvalidSignature when blob is truncated below expected size`() = runTest {
        val tamperEvents = mutableListOf<Pair<String, TamperEvent>>()
        val (storage, _, sigStore) = newStorage(onTamperDetected = { key, e -> tamperEvents += key to e })

        storage.write(keyOne, sampleSnapshotOne)
        sigStore.put(keyOne, sigStore.lastFor(keyOne)!!.copyOfRange(0, 10))

        assertThat(storage.readAll()).isEmpty()
        assertThat(tamperEvents).containsExactly(keyOne to TamperEvent.InvalidSignature)
    }

    @Test
    fun `readAll fires InvalidSignature when blob has trailing bytes beyond expected size`() = runTest {
        val tamperEvents = mutableListOf<Pair<String, TamperEvent>>()
        val (storage, _, sigStore) = newStorage(onTamperDetected = { key, e -> tamperEvents += key to e })

        storage.write(keyOne, sampleSnapshotOne)
        sigStore.put(keyOne, sigStore.lastFor(keyOne)!! + ByteArray(4) { 0x42 })

        assertThat(storage.readAll()).isEmpty()
        assertThat(tamperEvents).containsExactly(keyOne to TamperEvent.InvalidSignature)
    }

    @Test
    fun `one key's tamper does not affect other verified keys`() = runTest {
        val tamperEvents = mutableListOf<Pair<String, TamperEvent>>()
        val (storage, delegate, _) = newStorage(onTamperDetected = { key, e -> tamperEvents += key to e })

        storage.write(keyOne, sampleSnapshotOne)
        storage.write(keyTwo, sampleSnapshotTwo)
        delegate.put(keyOne, sampleSnapshotOne.copy(isEntitled = false))   // mutate keyOne's snapshot

        val verified = storage.readAll()
        assertThat(verified).containsExactly(keyTwo, sampleSnapshotTwo)
        assertThat(tamperEvents).containsExactly(keyOne to TamperEvent.InvalidSignature)
    }

    @Test
    fun `write does not persist snapshot when keyProvider sign throws`() = runTest {
        val delegate = InMemoryStorage<String>()
        val sigStore = InMemorySignatureStore()
        val storage = SignedEntitlementStorage(
            delegate = delegate,
            keyProvider = ThrowingKeyProvider,
            signatureStore = sigStore,
            keyToStorageId = { it },
        )

        try {
            storage.write(keyOne, sampleSnapshotOne)
            error("Expected IllegalStateException from sign()")
        } catch (e: IllegalStateException) {
            // expected
        }

        assertThat(delegate.last(keyOne)).isNull()
        assertThat(sigStore.lastFor(keyOne)).isNull()
    }

    @Test
    fun `write throws when keyProvider produces wrong-sized hmac`() = runTest {
        val storage = SignedEntitlementStorage(
            delegate = InMemoryStorage<String>(),
            keyProvider = object : HmacKeyProvider {
                override suspend fun sign(data: ByteArray): ByteArray = ByteArray(16) { 0x01 }
            },
            signatureStore = InMemorySignatureStore(),
            keyToStorageId = { it },
        )

        try {
            storage.write(keyOne, sampleSnapshotOne)
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("16-byte")
            assertThat(e.message).contains("32")
        }
    }

    @Test
    fun `migrateUnsignedSnapshot returns false when delegate has no snapshot`() = runTest {
        val delegate = InMemoryStorage<String>()
        val sigStore = InMemorySignatureStore()
        val keyProvider = FixedKeyHmacProvider(KEY_BYTES)

        val migrated = SignedEntitlementStorage.migrateUnsignedSnapshot(
            entitlementKey = keyOne,
            entitlementCacheKey = keyOne,
            delegate = delegate,
            keyProvider = keyProvider,
            signatureStore = sigStore,
        )

        assertThat(migrated).isFalse()
        assertThat(sigStore.lastFor(keyOne)).isNull()
    }

    @Test
    fun `migrateUnsignedSnapshot signs existing snapshot and subsequent reads succeed without callback`() = runTest {
        val delegate = InMemoryStorage<String>().also { it.put(keyOne, sampleSnapshotOne) }
        val sigStore = InMemorySignatureStore()
        val keyProvider = FixedKeyHmacProvider(KEY_BYTES)
        val tamperEvents = mutableListOf<Pair<String, TamperEvent>>()

        val migrated = SignedEntitlementStorage.migrateUnsignedSnapshot(
            entitlementKey = keyOne,
            entitlementCacheKey = keyOne,
            delegate = delegate,
            keyProvider = keyProvider,
            signatureStore = sigStore,
        )
        assertThat(migrated).isTrue()
        assertThat(sigStore.lastFor(keyOne)).isNotNull()

        val storage = SignedEntitlementStorage(
            delegate = delegate,
            keyProvider = keyProvider,
            signatureStore = sigStore,
            keyToStorageId = { it },
            onTamperDetected = { key, e -> tamperEvents += key to e },
        )
        assertThat(storage.readAll()).containsExactly(keyOne, sampleSnapshotOne)
        assertThat(tamperEvents).isEmpty()
    }

    @Test
    fun `migrateUnsignedSnapshot is a no-op when signature already present`() = runTest {
        val (storage, _, sigStore) = newStorage()
        storage.write(keyOne, sampleSnapshotOne)
        val firstBlob = sigStore.lastFor(keyOne)!!.copyOf()

        val migrated = SignedEntitlementStorage.migrateUnsignedSnapshot(
            entitlementKey = keyOne,
            entitlementCacheKey = keyOne,
            delegate = storage,
            keyProvider = FixedKeyHmacProvider(KEY_BYTES),
            signatureStore = sigStore,
        )

        assertThat(migrated).isFalse()
        assertThat(sigStore.lastFor(keyOne)).isEqualTo(firstBlob)
    }

    // -- helpers -----------------------------------------------------------

    private fun newStorage(
        onTamperDetected: (String, TamperEvent) -> Unit = { _, _ -> },
    ): Triple<SignedEntitlementStorage<String>, InMemoryStorage<String>, InMemorySignatureStore> {
        val delegate = InMemoryStorage<String>()
        val sigStore = InMemorySignatureStore()
        val storage = SignedEntitlementStorage(
            delegate = delegate,
            keyProvider = FixedKeyHmacProvider(KEY_BYTES),
            signatureStore = sigStore,
            keyToStorageId = { it },
            onTamperDetected = onTamperDetected,
        )
        return Triple(storage, delegate, sigStore)
    }

    private companion object {
        private val KEY_BYTES: ByteArray = ByteArray(32) { (it * 7 + 1).toByte() }
    }
}

private class InMemoryStorage<K : Any> : EntitlementStorage<K> {
    private val store: MutableMap<K, EntitlementSnapshot> = HashMap()

    fun last(key: K): EntitlementSnapshot? = store[key]

    fun put(key: K, snapshot: EntitlementSnapshot) {
        store[key] = snapshot
    }

    override suspend fun readAll(): Map<K, EntitlementSnapshot> = HashMap(store)

    override suspend fun write(key: K, snapshot: EntitlementSnapshot) {
        store[key] = snapshot
    }
}

private class InMemorySignatureStore : SignatureStore {
    private val store: MutableMap<String, ByteArray> = HashMap()

    fun lastFor(entitlementKey: String): ByteArray? = store[entitlementKey]

    fun put(entitlementKey: String, signature: ByteArray) {
        store[entitlementKey] = signature
    }

    override suspend fun readSignature(entitlementKey: String): ByteArray? = store[entitlementKey]

    override suspend fun writeSignature(entitlementKey: String, signature: ByteArray) {
        store[entitlementKey] = signature
    }

    override suspend fun clearSignature(entitlementKey: String) {
        store.remove(entitlementKey)
    }
}

private class FixedKeyHmacProvider(private val key: ByteArray) : HmacKeyProvider {
    override suspend fun sign(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}

private object ThrowingKeyProvider : HmacKeyProvider {
    override suspend fun sign(data: ByteArray): ByteArray =
        throw IllegalStateException("simulated key provider failure")
}
