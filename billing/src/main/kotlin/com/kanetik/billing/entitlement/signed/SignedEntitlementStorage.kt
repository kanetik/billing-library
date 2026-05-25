package com.kanetik.billing.entitlement.signed

import com.kanetik.billing.entitlement.EntitlementSnapshot
import com.kanetik.billing.entitlement.EntitlementStorage
import java.nio.ByteBuffer

/**
 * [EntitlementStorage] decorator that signs each snapshot on write and
 * verifies the signature on read, providing tamper resistance for the
 * cache's persisted state.
 *
 * Wraps any other [EntitlementStorage] implementation (DataStore, Room,
 * SharedPreferences, etc.). The library remains neutral on the persistence
 * choice; only the signing is centralised. The wrapped [delegate] continues
 * to own the snapshot bytes; the signature blob for each entitlement key
 * lives separately in [signatureStore].
 *
 * # Per-key signatures
 *
 * Each entitlement key in the cache has its own signature blob. The decorator
 * uses [keyToStorageId] to serialize `K` to a stable string identifier — that
 * string is the lookup key in [signatureStore] and is also included in the
 * canonical bytes that are HMAC'd, so a sig for key A applied to a snapshot
 * for key B fails verification (cross-key swap detection).
 *
 * Pick a stable [keyToStorageId]:
 *  - `K = String` → `{ it }`
 *  - `K = SomeEnum` → `{ it.name }` (the constant identifier; `toString()`
 *    is overridable and not safe)
 *  - `K = sealed class` → a stable discriminator field
 *
 * # Wire format
 *
 * The signature blob is `4 bytes version (BE int) || 32 bytes HMAC-SHA256`.
 * The version is included both in the blob (so verification knows how to
 * canonical-encode the snapshot) and inside the HMAC input (so it's
 * authenticated and an attacker can't downgrade the version). The per-version
 * snapshot canonical encoding is internal; the wire format is stable and
 * versioned so future field additions to [EntitlementSnapshot] don't
 * invalidate signatures written by older library versions. **v1** signatures
 * (legacy single-entitlement, no key in canonical bytes) continue to verify
 * on this build — useful for consumers upgrading from v0.1.x with a single
 * entitlement (`K = Unit`); their old sigs roundtrip without firing a tamper
 * event. New writes always use v2.
 *
 * # Read path
 *
 * The decorator omits a key's snapshot from [readAll] (the cache reads the
 * absent key as "no prior state for this entitlement") in any of these cases,
 * firing [onTamperDetected] with a typed [TamperEvent]:
 *
 *  - **[TamperEvent.MissingSignature]** — delegate returned a snapshot for
 *    the key but [signatureStore] holds no signature for that key. Most
 *    likely benign: a one-time read after upgrading from an unsigned
 *    configuration. Route this differently from [TamperEvent.InvalidSignature]
 *    in your telemetry. See [migrateUnsignedSnapshot] if you want to avoid
 *    the cold-start.
 *  - **[TamperEvent.InvalidSignature]** — both pieces present, HMAC failed
 *    to verify (or the blob was truncated / had trailing bytes). Strong
 *    tamper indicator; this is the event your alerting should pay attention
 *    to.
 *  - **[TamperEvent.UnsupportedVersion]** — blob declares a version this
 *    build of the library doesn't know how to verify. Implies forgery, a
 *    forward-incompatible blob written by a newer library version, or
 *    storage corruption.
 *
 * Verification is per-key: a tamper event on one key drops that key's
 * snapshot but does not affect the others. The cache still sees verified
 * snapshots for the unaffected keys.
 *
 * # Write path
 *
 * [write] computes the signature blob first (no side effects), then persists
 * the snapshot via [delegate], then persists the signature via [signatureStore].
 * Ordering signing first means a [keyProvider] failure (e.g., transient
 * Keystore key generation error) leaves both stores untouched for that key —
 * the next write retries from a consistent state. If the signature write
 * fails *after* the snapshot write succeeded, the next read sees a snapshot/
 * signature mismatch (or no signature on first ever write) and falls back to
 * the cold-start path for that key; the next `OwnedPurchases.Live` or
 * `OwnedPurchases.Recovered` re-confirms truth (often within the same launch,
 * once the billing connection establishes).
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
 * // Multi-entitlement: K is the product ID string.
 * val storage: EntitlementStorage<String> = SignedEntitlementStorage(
 *     delegate = MyDataStoreEntitlementStorage(context),
 *     keyProvider = KeystoreBackedKeyProvider.create(),
 *     signatureStore = SharedPreferencesSignatureStore(context),
 *     keyToStorageId = { it },
 *     onTamperDetected = { entitlementKey, event ->
 *         when (event) {
 *             TamperEvent.MissingSignature -> logger.info("First read for $entitlementKey")
 *             TamperEvent.InvalidSignature -> logger.warn("Possible tampering on $entitlementKey")
 *             is TamperEvent.UnsupportedVersion ->
 *                 logger.warn("Unknown signature version ${event.version} for $entitlementKey")
 *         }
 *     },
 * )
 * val cache = EntitlementCache(purchasesUpdates, storage, gracePolicy, productKeySelector)
 * ```
 *
 * @param delegate the underlying [EntitlementStorage] that holds the snapshot
 *   bytes. Owns serialization; the decorator does not touch the snapshot
 *   format the delegate uses.
 * @param keyProvider source of the HMAC key. See [HmacKeyProvider].
 * @param signatureStore per-entitlement-key persistence for signature blobs.
 *   See [SignatureStore].
 * @param keyToStorageId serializes `K` to a stable string identifier. The
 *   string is used both as the lookup key in [signatureStore] and is included
 *   in the v2 canonical bytes. **Must be injective** — two distinct `K` values
 *   must never serialize to the same string, or their snapshots / signatures
 *   collide in storage and verification behaves unpredictably. **Must also be
 *   stable across app upgrades** — renaming an enum constant breaks
 *   verification of that entitlement's old sigs. The library does not
 *   validate collisions defensively; injectivity and stability are caller
 *   contracts.
 * @param onTamperDetected invoked from [readAll] when verification fails for
 *   a key. Receives the entitlement key (as serialized via [keyToStorageId])
 *   and the typed event. Runs on whatever dispatcher the read is invoked
 *   from. Intended for logging / telemetry; do not throw.
 */
public class SignedEntitlementStorage<K : Any>(
    private val delegate: EntitlementStorage<K>,
    private val keyProvider: HmacKeyProvider,
    private val signatureStore: SignatureStore,
    private val keyToStorageId: (K) -> String,
    private val onTamperDetected: (entitlementKey: String, event: TamperEvent) -> Unit = { _, _ -> },
) : EntitlementStorage<K> {

    override suspend fun readAll(): Map<K, EntitlementSnapshot> {
        val raw = delegate.readAll()
        if (raw.isEmpty()) return emptyMap()
        val verified = LinkedHashMap<K, EntitlementSnapshot>(raw.size)
        for ((key, snapshot) in raw) {
            val entitlementKey = keyToStorageId(key)
            val ok = verifySnapshot(entitlementKey, snapshot)
            if (ok) verified[key] = snapshot
        }
        return verified
    }

    override suspend fun write(key: K, snapshot: EntitlementSnapshot) {
        val entitlementKey = keyToStorageId(key)
        // Sign first (no side effects), then persist. A keyProvider failure —
        // e.g., transient AndroidKeyStore key generation flake, ServerSeeded
        // fetcher throwing — would otherwise leave delegate updated with a
        // snapshot the next read can't verify. Computing the blob up front
        // makes signing failures fully retriable.
        val blob = signSnapshot(snapshot, entitlementKey, keyProvider)
        delegate.write(key, snapshot)
        signatureStore.writeSignature(entitlementKey, blob)
    }

    private suspend fun verifySnapshot(entitlementKey: String, snapshot: EntitlementSnapshot): Boolean {
        val blob = signatureStore.readSignature(entitlementKey)
        if (blob == null) {
            onTamperDetected(entitlementKey, TamperEvent.MissingSignature)
            return false
        }
        // Wire format is fixed: VERSION_PREFIX_SIZE + HMAC_SHA256_SIZE = exactly
        // SIGNATURE_BLOB_SIZE bytes. Anything else is malformed — including
        // trailing bytes, which would otherwise be silently folded into the
        // HMAC slice and verified against the wrong shape.
        if (blob.size != SIGNATURE_BLOB_SIZE) {
            onTamperDetected(entitlementKey, TamperEvent.InvalidSignature)
            return false
        }

        val version = ByteBuffer.wrap(blob, 0, VERSION_PREFIX_SIZE).int
        if (version < MIN_VERSION || version > SnapshotCanonicalBytes.MAX_SUPPORTED_VERSION) {
            onTamperDetected(entitlementKey, TamperEvent.UnsupportedVersion(version))
            return false
        }

        val canonical = SnapshotCanonicalBytes.encode(snapshot, entitlementKey, version)
        val hmac = blob.copyOfRange(VERSION_PREFIX_SIZE, blob.size)
        val verified = keyProvider.verify(canonical, hmac)
        if (!verified) {
            onTamperDetected(entitlementKey, TamperEvent.InvalidSignature)
            return false
        }
        return true
    }

    public companion object {

        /**
         * Migrates an existing unsigned snapshot for [entitlementKey] —
         * typically left over from a release that didn't yet wrap [delegate]
         * in [SignedEntitlementStorage] — by reading it once and writing a
         * fresh signature blob via [signatureStore]. Subsequent reads through
         * a normally-configured [SignedEntitlementStorage] over the same trio
         * of dependencies will succeed without firing the tamper callback.
         *
         * Intended for one-shot use on first launch after upgrade, per
         * entitlement. The caller is responsible for guarding with a
         * "migration applied" marker (e.g., a SharedPreferences boolean) so
         * this runs at most once per install. `migrateUnsignedSnapshot` is
         * `suspend` (it reads and writes through the same dispatchers your
         * storage normally uses), so call it from a coroutine — and call it
         * **before** constructing the [SignedEntitlementStorage] that wraps
         * the same trio, so the cache's first read sees the freshly-written
         * signature instead of firing [TamperEvent.MissingSignature].
         *
         * # Idempotency
         *
         * If [signatureStore] already contains a blob for [entitlementKey],
         * this method does nothing and returns `false` — calling it more than
         * once is safe.
         *
         * # Security tradeoff
         *
         * This trusts the existing snapshot. If the device was tampered with
         * before this call ran, the resulting signature blesses the tampered
         * data. Skip this helper and accept the cold-start path
         * ([TamperEvent.MissingSignature] → null read → live re-confirm) if
         * pre-upgrade tampering is in your threat model.
         *
         * # Multi-entitlement
         *
         * Each key migrates independently. Loop your known keys; the helper
         * is no-op for any key whose snapshot is already absent from
         * [delegate].
         *
         * @param entitlementKey the serialized entitlement key string (the
         *   same one a [SignedEntitlementStorage] would compute via its
         *   `keyToStorageId` lambda for the corresponding `K`). For a
         *   single-entitlement migration from v0.1.x, this is whatever your
         *   new `keyToStorageId(yourSingleKey)` produces.
         * @return `true` if a snapshot was found and a signature was written;
         *   `false` if the delegate had no snapshot to migrate, or if a
         *   signature already existed for [entitlementKey].
         */
        public suspend fun <K : Any> migrateUnsignedSnapshot(
            entitlementKey: String,
            entitlementCacheKey: K,
            delegate: EntitlementStorage<K>,
            keyProvider: HmacKeyProvider,
            signatureStore: SignatureStore,
        ): Boolean {
            // Idempotent: skip the (potentially expensive) snapshot read when a
            // signature is already in place. Repeated calls become a single
            // signature read.
            if (signatureStore.readSignature(entitlementKey) != null) return false
            val snapshot = delegate.readAll()[entitlementCacheKey] ?: return false

            val blob = signSnapshot(snapshot, entitlementKey, keyProvider)
            signatureStore.writeSignature(entitlementKey, blob)
            return true
        }

        private suspend fun signSnapshot(
            snapshot: EntitlementSnapshot,
            entitlementKey: String,
            keyProvider: HmacKeyProvider,
        ): ByteArray {
            val version = SnapshotCanonicalBytes.CURRENT_VERSION
            val canonical = SnapshotCanonicalBytes.encode(snapshot, entitlementKey, version)
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

        // Lowest version this build will accept on read. v1 (single-entitlement
        // legacy) and v2 (multi-entitlement, key in canonical bytes) are both
        // supported. Allowing v0 or negatives would let a corrupted / forged
        // blob crash read() via encode() throwing.
        private const val MIN_VERSION = 1
    }
}

/**
 * Reason that [SignedEntitlementStorage.readAll] couldn't return a valid
 * snapshot for a given entitlement key. Passed to the `onTamperDetected`
 * callback alongside the affected key.
 *
 * Distinguishing these cases lets consumer telemetry route them differently:
 * [MissingSignature] is usually benign (one-time migration), while
 * [InvalidSignature] is the strong tamper indicator that should drive alerts.
 */
public sealed interface TamperEvent {

    /**
     * The delegate returned a snapshot for this entitlement key, but
     * [SignatureStore] held no signature for it. Typically a one-time first
     * read after upgrading to signed storage from a release that wasn't
     * wrapping its [EntitlementStorage]. See
     * [SignedEntitlementStorage.migrateUnsignedSnapshot] for an opt-in
     * migration path that avoids this case.
     */
    public object MissingSignature : TamperEvent

    /**
     * The delegate returned a snapshot for this entitlement key and the
     * signature blob was present and the right shape, but HMAC verification
     * failed. Strong tamper indicator — either the snapshot or the signature
     * was modified after the last legitimate write, or a cross-key sig swap
     * was attempted. (Also fires if the blob is truncated below or extended
     * beyond the expected size; that's still consistent with a tamper
     * attempt that corrupted the signature file.)
     */
    public object InvalidSignature : TamperEvent

    /**
     * The signature blob for this entitlement key declared a version this
     * build of the library can't verify (either above the highest version
     * this build supports or below `1`). Implies forgery, blob corruption,
     * or a forward-incompatible blob written by a newer library version that
     * the app was later downgraded from.
     */
    public data class UnsupportedVersion(val version: Int) : TamperEvent
}
