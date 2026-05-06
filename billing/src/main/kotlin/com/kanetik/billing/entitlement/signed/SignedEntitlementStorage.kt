package com.kanetik.billing.entitlement.signed

import com.kanetik.billing.entitlement.EntitlementSnapshot
import com.kanetik.billing.entitlement.EntitlementStorage
import java.nio.ByteBuffer

/**
 * [EntitlementStorage] decorator that signs the snapshot on write and
 * verifies the signature on read, providing tamper resistance for the
 * cache's persisted state.
 *
 * Wraps any other [EntitlementStorage] implementation (DataStore, Room,
 * SharedPreferences, etc.). The library remains neutral on the persistence
 * choice; only the signing is centralised. The wrapped [delegate] continues
 * to own the snapshot bytes; the signature blob lives separately in
 * [signatureStore].
 *
 * # Wire format
 *
 * The signature blob is `4 bytes version (BE int) || 32 bytes HMAC-SHA256`.
 * The version is included both in the blob (so verification knows how to
 * canonical-encode the snapshot) and inside the HMAC input (so it's
 * authenticated and an attacker can't downgrade the version). See
 * [SnapshotCanonicalBytes] for the per-version snapshot encoding and the
 * procedure for adding a new version when [EntitlementSnapshot] grows a
 * field.
 *
 * # Read path
 *
 * The decorator returns `null` (the cache reads it as "no prior state") in
 * any of these cases, firing [onTamperDetected] with a typed [TamperEvent]:
 *
 *  - **[TamperEvent.MissingSignature]** — delegate returned a snapshot but
 *    [signatureStore] is empty. Most likely benign: a one-time read after
 *    upgrading from an unsigned configuration. Route this differently from
 *    [TamperEvent.InvalidSignature] in your telemetry. See
 *    [migrateUnsignedSnapshot] if you want to avoid the cold-start.
 *  - **[TamperEvent.InvalidSignature]** — both pieces present, HMAC failed
 *    to verify (or the blob was truncated). Strong tamper indicator; this
 *    is the event your alerting should pay attention to.
 *  - **[TamperEvent.UnsupportedVersion]** — blob declares a version this
 *    build of the library doesn't know how to verify. Implies forgery, a
 *    forward-incompatible blob written by a newer library version, or
 *    storage corruption.
 *
 * If the delegate returns `null` (no prior snapshot at all), the decorator
 * also returns `null` but does NOT fire [onTamperDetected] — that's the
 * legitimate first-run case.
 *
 * # Write path
 *
 * `write` always writes the snapshot to the delegate first, then writes the
 * signature blob. If the signature write fails after the snapshot write
 * succeeded, the next read sees an unsigned snapshot and falls back to the
 * cold-start path; `OwnedPurchases.Live` re-confirms on next launch.
 *
 * # Threat-model caveats
 *
 * See [HmacKeyProvider] for the full set. Briefly:
 *
 *  - The library cannot enforce key non-extractability. Use
 *    [KeystoreBackedKeyProvider] (the easy default) unless you know what you're
 *    doing.
 *  - Not a replacement for server-side validation. The Voided Purchases API
 *    and RTDN remain the right fit for stronger threat models.
 *  - Not a replacement for Tink. This helper is scoped to [EntitlementStorage].
 *
 * # Example
 *
 * ```kotlin
 * val storage: EntitlementStorage = SignedEntitlementStorage(
 *     delegate = MyDataStoreEntitlementStorage(context),
 *     keyProvider = KeystoreBackedKeyProvider.create(),
 *     signatureStore = SharedPreferencesSignatureStore(context),
 *     onTamperDetected = { event ->
 *         when (event) {
 *             TamperEvent.MissingSignature -> logger.info("First read after upgrade")
 *             TamperEvent.InvalidSignature -> logger.warn("Possible tampering detected")
 *             is TamperEvent.UnsupportedVersion ->
 *                 logger.warn("Unknown signature version ${event.version}")
 *         }
 *     },
 * )
 * val cache = EntitlementCache(purchasesUpdates, storage, gracePolicy, productPredicate)
 * ```
 *
 * @param delegate the underlying [EntitlementStorage] that holds the snapshot
 *   bytes. Owns serialization; the decorator does not touch the snapshot
 *   format the delegate uses.
 * @param keyProvider source of the HMAC key. See [HmacKeyProvider].
 * @param signatureStore persistence for the signature blob. See
 *   [SignatureStore].
 * @param onTamperDetected invoked from [read] when verification fails. Runs
 *   on whatever dispatcher the read is invoked from. Intended for logging /
 *   telemetry; do not throw.
 */
public class SignedEntitlementStorage(
    private val delegate: EntitlementStorage,
    private val keyProvider: HmacKeyProvider,
    private val signatureStore: SignatureStore,
    private val onTamperDetected: (TamperEvent) -> Unit = {},
) : EntitlementStorage {

    override suspend fun read(): EntitlementSnapshot? {
        val snapshot = delegate.read() ?: return null

        val blob = signatureStore.readSignature()
        if (blob == null) {
            onTamperDetected(TamperEvent.MissingSignature)
            return null
        }
        // Wire format is fixed: VERSION_PREFIX_SIZE + HMAC_SHA256_SIZE = exactly
        // SIGNATURE_BLOB_SIZE bytes. Anything else is malformed — including
        // trailing bytes, which would otherwise be silently folded into the
        // HMAC slice and verified against the wrong shape.
        if (blob.size != SIGNATURE_BLOB_SIZE) {
            onTamperDetected(TamperEvent.InvalidSignature)
            return null
        }

        val version = ByteBuffer.wrap(blob, 0, VERSION_PREFIX_SIZE).int
        if (version < MIN_VERSION || version > SnapshotCanonicalBytes.MAX_SUPPORTED_VERSION) {
            onTamperDetected(TamperEvent.UnsupportedVersion(version))
            return null
        }

        val canonical = SnapshotCanonicalBytes.encode(snapshot, version)
        val hmac = blob.copyOfRange(VERSION_PREFIX_SIZE, blob.size)
        val verified = keyProvider.verify(canonical, hmac)
        if (!verified) {
            onTamperDetected(TamperEvent.InvalidSignature)
            return null
        }
        return snapshot
    }

    override suspend fun write(snapshot: EntitlementSnapshot) {
        delegate.write(snapshot)
        val blob = signSnapshot(snapshot, keyProvider)
        signatureStore.writeSignature(blob)
    }

    public companion object {

        /**
         * Migrates an existing unsigned snapshot — typically left over from a
         * release that didn't yet wrap [delegate] in [SignedEntitlementStorage]
         * — by reading it once and writing a fresh signature blob via
         * [signatureStore]. Subsequent reads through a normally-configured
         * [SignedEntitlementStorage] over the same trio of dependencies will
         * succeed without firing the tamper callback.
         *
         * Intended for one-shot use on first launch after upgrade. The caller
         * is responsible for guarding with a "migration applied" marker (e.g.,
         * a SharedPreferences boolean) so this runs at most once per install.
         * `migrateUnsignedSnapshot` is `suspend` (it reads and writes through
         * the same dispatchers your storage normally uses), so call it from a
         * coroutine — and call it **before** constructing the
         * [SignedEntitlementStorage] that wraps the same trio, so the cache's
         * first read sees the freshly-written signature instead of firing
         * [TamperEvent.MissingSignature]:
         *
         * ```kotlin
         * suspend fun bootstrapEntitlementStorage(): EntitlementStorage {
         *     val rawStorage = MyDataStoreEntitlementStorage(context)
         *     val keyProvider = KeystoreBackedKeyProvider.create()
         *     val sigStore = SharedPreferencesSignatureStore(context)
         *
         *     if (!prefs.getBoolean("entitlement_signed_migrated", false)) {
         *         SignedEntitlementStorage.migrateUnsignedSnapshot(
         *             rawStorage, keyProvider, sigStore,
         *         )
         *         prefs.edit().putBoolean("entitlement_signed_migrated", true).apply()
         *     }
         *     return SignedEntitlementStorage(rawStorage, keyProvider, sigStore)
         * }
         * ```
         *
         * # Idempotency
         *
         * If [signatureStore] already contains a blob, this method does
         * nothing and returns `false` — calling it more than once is safe.
         *
         * # Security tradeoff
         *
         * This trusts the existing snapshot. If the device was tampered with
         * before this call ran, the resulting signature blesses the tampered
         * data. Skip this helper and accept the cold-start path
         * ([TamperEvent.MissingSignature] → null read → live re-confirm) if
         * pre-upgrade tampering is in your threat model.
         *
         * @return `true` if a snapshot was found and a signature was written;
         *   `false` if the delegate had no snapshot to migrate, or if a
         *   signature already existed.
         */
        public suspend fun migrateUnsignedSnapshot(
            delegate: EntitlementStorage,
            keyProvider: HmacKeyProvider,
            signatureStore: SignatureStore,
        ): Boolean {
            // Idempotent: skip the (potentially expensive) snapshot read when a
            // signature is already in place. Repeated calls become a single
            // signature read.
            if (signatureStore.readSignature() != null) return false
            val snapshot = delegate.read() ?: return false

            val blob = signSnapshot(snapshot, keyProvider)
            signatureStore.writeSignature(blob)
            return true
        }

        private suspend fun signSnapshot(
            snapshot: EntitlementSnapshot,
            keyProvider: HmacKeyProvider,
        ): ByteArray {
            val version = SnapshotCanonicalBytes.CURRENT_VERSION
            val canonical = SnapshotCanonicalBytes.encode(snapshot, version)
            val hmac = keyProvider.sign(canonical)
            // The wire format is anchored to HMAC-SHA256's 32-byte output; an
            // HmacKeyProvider that returns a different size would silently
            // produce blobs the reader doesn't define. Fail fast at the writer
            // so a misconfigured custom provider surfaces as a contract
            // violation rather than corrupt persistence.
            require(hmac.size == HMAC_SHA256_SIZE) {
                "HmacKeyProvider produced ${hmac.size}-byte signature; expected $HMAC_SHA256_SIZE (HMAC-SHA256)"
            }
            val blob = ByteBuffer.allocate(SIGNATURE_BLOB_SIZE)
            blob.putInt(version)
            blob.put(hmac)
            return blob.array()
        }

        private const val VERSION_PREFIX_SIZE = 4
        private const val HMAC_SHA256_SIZE = 32
        private const val SIGNATURE_BLOB_SIZE = VERSION_PREFIX_SIZE + HMAC_SHA256_SIZE

        // Lowest version this build will accept on read. SnapshotCanonicalBytes
        // currently knows v1; allowing v0 or negatives would let a corrupted /
        // forged blob crash read() via encode() throwing.
        private const val MIN_VERSION = 1
    }
}

/**
 * Reason that [SignedEntitlementStorage.read] couldn't return a valid
 * snapshot. Passed to the `onTamperDetected` callback.
 *
 * Distinguishing these cases lets consumer telemetry route them differently:
 * [MissingSignature] is usually benign (one-time migration), while
 * [InvalidSignature] is the strong tamper indicator that should drive alerts.
 */
public sealed interface TamperEvent {

    /**
     * The delegate returned a snapshot, but [SignatureStore] held no
     * signature. Typically a one-time first read after upgrading to signed
     * storage from a release that wasn't wrapping its [EntitlementStorage].
     * See [SignedEntitlementStorage.migrateUnsignedSnapshot] for an opt-in
     * migration path that avoids this case.
     */
    public object MissingSignature : TamperEvent

    /**
     * The delegate returned a snapshot and the signature blob was present and
     * the right shape, but HMAC verification failed. Strong tamper indicator
     * — either the snapshot or the signature was modified after the last
     * legitimate write. (Also fires if the blob is truncated below the
     * minimum size; that's still consistent with a tamper attempt that
     * corrupted the signature file.)
     */
    public object InvalidSignature : TamperEvent

    /**
     * The signature blob declared a version higher than this build of the
     * library knows how to verify. Implies forgery, blob corruption, or a
     * forward-incompatible blob written by a newer library version that
     * downgraded.
     */
    public data class UnsupportedVersion(val version: Int) : TamperEvent
}
