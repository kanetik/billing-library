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

    private val sampleSnapshot = EntitlementSnapshot(
        isEntitled = true,
        confirmedAtMs = 1_700_000_000_000L,
        purchaseToken = "tok-1",
    )

    @Test
    fun `write then read roundtrips the same snapshot`() = runTest {
        val (storage, delegate, sigStore) = newStorage()

        storage.write(sampleSnapshot)

        assertThat(delegate.lastWritten).isEqualTo(sampleSnapshot)
        assertThat(sigStore.last).isNotNull()
        assertThat(storage.read()).isEqualTo(sampleSnapshot)
    }

    @Test
    fun `read returns null without firing callback when delegate has no snapshot`() = runTest {
        val tamperEvents = mutableListOf<TamperEvent>()
        val (storage, _, _) = newStorage(onTamperDetected = { tamperEvents += it })

        assertThat(storage.read()).isNull()
        assertThat(tamperEvents).isEmpty()
    }

    @Test
    fun `read fires MissingSignature when snapshot present but signature missing`() = runTest {
        val tamperEvents = mutableListOf<TamperEvent>()
        val (storage, delegate, _) = newStorage(onTamperDetected = { tamperEvents += it })

        delegate.lastWritten = sampleSnapshot

        assertThat(storage.read()).isNull()
        assertThat(tamperEvents).containsExactly(TamperEvent.MissingSignature)
    }

    @Test
    fun `read fires InvalidSignature when snapshot mutated after signing`() = runTest {
        val tamperEvents = mutableListOf<TamperEvent>()
        val (storage, delegate, _) = newStorage(onTamperDetected = { tamperEvents += it })

        storage.write(sampleSnapshot)
        delegate.lastWritten = sampleSnapshot.copy(isEntitled = false)

        assertThat(storage.read()).isNull()
        assertThat(tamperEvents).containsExactly(TamperEvent.InvalidSignature)
    }

    @Test
    fun `read fires InvalidSignature when signature byte mutated`() = runTest {
        val tamperEvents = mutableListOf<TamperEvent>()
        val (storage, _, sigStore) = newStorage(onTamperDetected = { tamperEvents += it })

        storage.write(sampleSnapshot)
        val mutated = sigStore.last!!.copyOf()
        mutated[mutated.size - 1] = (mutated[mutated.size - 1].toInt() xor 0xff).toByte()
        sigStore.last = mutated

        assertThat(storage.read()).isNull()
        assertThat(tamperEvents).containsExactly(TamperEvent.InvalidSignature)
    }

    @Test
    fun `read fires UnsupportedVersion when blob declares version above MAX_SUPPORTED_VERSION`() = runTest {
        val tamperEvents = mutableListOf<TamperEvent>()
        val (storage, _, sigStore) = newStorage(onTamperDetected = { tamperEvents += it })

        storage.write(sampleSnapshot)
        val futureBlob = ByteBuffer.allocate(36).apply {
            putInt(SnapshotCanonicalBytes.MAX_SUPPORTED_VERSION + 1)
            put(sigStore.last!!.copyOfRange(4, 36))
        }.array()
        sigStore.last = futureBlob

        assertThat(storage.read()).isNull()
        assertThat(tamperEvents).containsExactly(
            TamperEvent.UnsupportedVersion(SnapshotCanonicalBytes.MAX_SUPPORTED_VERSION + 1),
        )
    }

    @Test
    fun `read fires UnsupportedVersion when blob declares version 0`() = runTest {
        val tamperEvents = mutableListOf<TamperEvent>()
        val (storage, _, sigStore) = newStorage(onTamperDetected = { tamperEvents += it })

        storage.write(sampleSnapshot)
        val zeroVersionBlob = ByteBuffer.allocate(36).apply {
            putInt(0)
            put(sigStore.last!!.copyOfRange(4, 36))
        }.array()
        sigStore.last = zeroVersionBlob

        assertThat(storage.read()).isNull()
        assertThat(tamperEvents).containsExactly(TamperEvent.UnsupportedVersion(0))
    }

    @Test
    fun `read fires UnsupportedVersion when blob declares negative version`() = runTest {
        val tamperEvents = mutableListOf<TamperEvent>()
        val (storage, _, sigStore) = newStorage(onTamperDetected = { tamperEvents += it })

        storage.write(sampleSnapshot)
        val negativeVersionBlob = ByteBuffer.allocate(36).apply {
            putInt(-1)
            put(sigStore.last!!.copyOfRange(4, 36))
        }.array()
        sigStore.last = negativeVersionBlob

        assertThat(storage.read()).isNull()
        assertThat(tamperEvents).containsExactly(TamperEvent.UnsupportedVersion(-1))
    }

    @Test
    fun `read fires InvalidSignature when blob is truncated below expected size`() = runTest {
        val tamperEvents = mutableListOf<TamperEvent>()
        val (storage, _, sigStore) = newStorage(onTamperDetected = { tamperEvents += it })

        storage.write(sampleSnapshot)
        sigStore.last = sigStore.last!!.copyOfRange(0, 10)

        assertThat(storage.read()).isNull()
        assertThat(tamperEvents).containsExactly(TamperEvent.InvalidSignature)
    }

    @Test
    fun `read fires InvalidSignature when blob has trailing bytes beyond expected size`() = runTest {
        val tamperEvents = mutableListOf<TamperEvent>()
        val (storage, _, sigStore) = newStorage(onTamperDetected = { tamperEvents += it })

        storage.write(sampleSnapshot)
        sigStore.last = sigStore.last!! + ByteArray(4) { 0x42 }

        assertThat(storage.read()).isNull()
        assertThat(tamperEvents).containsExactly(TamperEvent.InvalidSignature)
    }

    @Test
    fun `write throws when keyProvider produces wrong-sized hmac`() = runTest {
        val storage = SignedEntitlementStorage(
            delegate = InMemoryStorage(),
            keyProvider = object : HmacKeyProvider {
                override suspend fun sign(data: ByteArray): ByteArray = ByteArray(16) { 0x01 }
            },
            signatureStore = InMemorySignatureStore(),
        )

        try {
            storage.write(sampleSnapshot)
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("16-byte")
            assertThat(e.message).contains("32")
        }
    }

    @Test
    fun `migrateUnsignedSnapshot returns false when delegate has no snapshot`() = runTest {
        val delegate = InMemoryStorage()
        val sigStore = InMemorySignatureStore()
        val keyProvider = FixedKeyHmacProvider(KEY_BYTES)

        val migrated = SignedEntitlementStorage.migrateUnsignedSnapshot(delegate, keyProvider, sigStore)

        assertThat(migrated).isFalse()
        assertThat(sigStore.last).isNull()
    }

    @Test
    fun `migrateUnsignedSnapshot signs existing snapshot and subsequent reads succeed without callback`() = runTest {
        val delegate = InMemoryStorage().also { it.lastWritten = sampleSnapshot }
        val sigStore = InMemorySignatureStore()
        val keyProvider = FixedKeyHmacProvider(KEY_BYTES)
        val tamperEvents = mutableListOf<TamperEvent>()

        val migrated = SignedEntitlementStorage.migrateUnsignedSnapshot(delegate, keyProvider, sigStore)
        assertThat(migrated).isTrue()
        assertThat(sigStore.last).isNotNull()

        val storage = SignedEntitlementStorage(
            delegate, keyProvider, sigStore,
            onTamperDetected = { tamperEvents += it },
        )
        assertThat(storage.read()).isEqualTo(sampleSnapshot)
        assertThat(tamperEvents).isEmpty()
    }

    @Test
    fun `migrateUnsignedSnapshot does not read delegate when signature already present`() = runTest {
        val keyProvider = FixedKeyHmacProvider(KEY_BYTES)
        val sigStore = InMemorySignatureStore().also { it.last = ByteArray(36) }
        val delegate = object : EntitlementStorage {
            var readCalls = 0
            override suspend fun read(): EntitlementSnapshot? {
                readCalls++; return null
            }
            override suspend fun write(snapshot: EntitlementSnapshot) = error("unexpected")
        }

        val migrated = SignedEntitlementStorage.migrateUnsignedSnapshot(delegate, keyProvider, sigStore)

        assertThat(migrated).isFalse()
        assertThat(delegate.readCalls).isEqualTo(0)
    }

    @Test
    fun `migrateUnsignedSnapshot is a no-op when signature already present`() = runTest {
        val (storage, _, sigStore) = newStorage()
        storage.write(sampleSnapshot)
        val firstBlob = sigStore.last!!.copyOf()

        val migrated = SignedEntitlementStorage.migrateUnsignedSnapshot(
            delegate = storage, // re-using through the decorator is fine; delegate.read returns the same snapshot
            keyProvider = FixedKeyHmacProvider(KEY_BYTES),
            signatureStore = sigStore,
        )

        assertThat(migrated).isFalse()
        assertThat(sigStore.last).isEqualTo(firstBlob)
    }

    // -- helpers -----------------------------------------------------------

    private fun newStorage(
        onTamperDetected: (TamperEvent) -> Unit = {},
    ): Triple<SignedEntitlementStorage, InMemoryStorage, InMemorySignatureStore> {
        val delegate = InMemoryStorage()
        val sigStore = InMemorySignatureStore()
        val storage = SignedEntitlementStorage(
            delegate = delegate,
            keyProvider = FixedKeyHmacProvider(KEY_BYTES),
            signatureStore = sigStore,
            onTamperDetected = onTamperDetected,
        )
        return Triple(storage, delegate, sigStore)
    }

    private companion object {
        private val KEY_BYTES: ByteArray = ByteArray(32) { (it * 7 + 1).toByte() }
    }
}

private class InMemoryStorage : EntitlementStorage {
    var lastWritten: EntitlementSnapshot? = null

    override suspend fun read(): EntitlementSnapshot? = lastWritten

    override suspend fun write(snapshot: EntitlementSnapshot) {
        lastWritten = snapshot
    }
}

private class InMemorySignatureStore : SignatureStore {
    var last: ByteArray? = null

    override suspend fun readSignature(): ByteArray? = last

    override suspend fun writeSignature(signature: ByteArray) {
        last = signature
    }
}

private class FixedKeyHmacProvider(private val key: ByteArray) : HmacKeyProvider {
    override suspend fun sign(data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }
}
