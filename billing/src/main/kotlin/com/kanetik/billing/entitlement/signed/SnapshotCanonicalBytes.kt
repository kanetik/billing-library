package com.kanetik.billing.entitlement.signed

import com.kanetik.billing.entitlement.EntitlementSnapshot
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Internal helper: deterministic byte encoding of an [EntitlementSnapshot]
 * tagged with a schema version, used as the input to HMAC sign / verify.
 *
 * # Wire formats
 *
 * ## v1 (legacy — single-entitlement)
 * ```
 * canonical_v1 := version_be(4 bytes)
 *              || is_entitled(1 byte: 0 or 1)
 *              || confirmed_at_ms(8 bytes BE long)
 *              || token_len(4 bytes BE int; -1 for null)
 *              || token_utf8(token_len bytes; absent when token_len == -1)
 * ```
 *
 * Written by the v0.1.x single-entitlement cache. Does **not** include the
 * entitlement key (there was only one entitlement at the time). Read-only on
 * this build — new writes always use [CURRENT_VERSION]. A consumer upgrading
 * from v0.1.x with `K = Unit` (or any single-key shape) will see their old
 * v1 sigs continue to verify, because the v1 encoder doesn't reference the
 * entitlement key.
 *
 * ## v2 (current — multi-entitlement)
 * ```
 * canonical_v2 := version_be(4 bytes)
 *              || key_len(4 bytes BE int; >= 0)
 *              || key_utf8(key_len bytes)
 *              || is_entitled(1 byte: 0 or 1)
 *              || confirmed_at_ms(8 bytes BE long)
 *              || token_len(4 bytes BE int; -1 for null)
 *              || token_utf8(token_len bytes; absent when token_len == -1)
 * ```
 *
 * Includes the serialized entitlement key (via [SignedEntitlementStorage]'s
 * `keyToStorageId`) in the canonical bytes. This makes cross-key signature
 * swap detectable — a v2 sig for entitlement A applied to a v2 snapshot for
 * entitlement B fails verification because the key bytes differ.
 *
 * The version int is part of the bytes the HMAC authenticates (not just a
 * separately-stored prefix), so an attacker can't downgrade a v2 signature
 * to v1 or vice versa without breaking verification.
 *
 * # Adding a field to [EntitlementSnapshot]
 *
 * 1. Add the field to [EntitlementSnapshot] (must be nullable or have a
 *    documented absent-default consistent with prior reads — additive
 *    evolution only, no breaking shape changes).
 * 2. Bump [CURRENT_VERSION] (e.g., 2 → 3).
 * 3. Add a `3 -> ...` branch to [encode] that writes the new field.
 * 4. Leave the `2 -> ...` and `1 -> ...` branches untouched. They keep
 *    verifying old signatures by canonical-encoding only the version-known
 *    fields. A snapshot signed at an older version will continue to verify
 *    after the library upgrade because the deserialized snapshot's new field
 *    defaults to its absent-value, which the old encoder ignores.
 * 5. Add a roundtrip test: sign at the new version, verify the new version.
 *    Sign at each prior version, read back through the new encode, verify
 *    still passes.
 *
 * The verifier does not own snapshot deserialization (the consumer's
 * [com.kanetik.billing.entitlement.EntitlementStorage] does). It receives the
 * current-shape snapshot from the delegate and re-derives canonical bytes for
 * whatever version the persisted signature blob declares.
 */
internal object SnapshotCanonicalBytes {

    /**
     * The version stamped into all newly-written signatures. Bump when adding
     * a field to [EntitlementSnapshot]; see the file-level KDoc.
     */
    const val CURRENT_VERSION: Int = 2

    /** Largest version this build of the library knows how to verify. */
    const val MAX_SUPPORTED_VERSION: Int = CURRENT_VERSION

    /**
     * Encodes [snapshot] for signing at the named [version], scoped to
     * [entitlementKey]. The v1 encoder ignores [entitlementKey] (single-
     * entitlement legacy format); v2+ includes it in the canonical bytes.
     *
     * @throws IllegalArgumentException if [version] is unknown to this build.
     *   Callers verifying a persisted signature should range-check the parsed
     *   version against [MAX_SUPPORTED_VERSION] before invoking and surface a
     *   tamper / unsupported-version event instead of letting this throw.
     */
    fun encode(snapshot: EntitlementSnapshot, entitlementKey: String, version: Int): ByteArray {
        return when (version) {
            1 -> encodeV1(snapshot)
            2 -> encodeV2(snapshot, entitlementKey)
            else -> throw IllegalArgumentException("Unknown snapshot version: $version")
        }
    }

    private fun encodeV1(snapshot: EntitlementSnapshot): ByteArray {
        val tokenBytes: ByteArray? = snapshot.purchaseToken?.toByteArray(StandardCharsets.UTF_8)
        val tokenLen = tokenBytes?.size ?: -1
        val tokenSerializedSize = tokenBytes?.size ?: 0

        // 4 (version) + 1 (isEntitled) + 8 (confirmedAtMs) + 4 (tokenLen) + tokenBytes
        val totalSize = 4 + 1 + 8 + 4 + tokenSerializedSize
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.putInt(1)
        buffer.put(if (snapshot.isEntitled) 1.toByte() else 0.toByte())
        buffer.putLong(snapshot.confirmedAtMs)
        buffer.putInt(tokenLen)
        if (tokenBytes != null) {
            buffer.put(tokenBytes)
        }
        return buffer.array()
    }

    private fun encodeV2(snapshot: EntitlementSnapshot, entitlementKey: String): ByteArray {
        val keyBytes = entitlementKey.toByteArray(StandardCharsets.UTF_8)
        val tokenBytes: ByteArray? = snapshot.purchaseToken?.toByteArray(StandardCharsets.UTF_8)
        val tokenLen = tokenBytes?.size ?: -1
        val tokenSerializedSize = tokenBytes?.size ?: 0

        // 4 (version) + 4 (keyLen) + keyBytes + 1 (isEntitled) + 8 (confirmedAtMs)
        //   + 4 (tokenLen) + tokenBytes
        val totalSize = 4 + 4 + keyBytes.size + 1 + 8 + 4 + tokenSerializedSize
        val buffer = ByteBuffer.allocate(totalSize)
        buffer.putInt(2)
        buffer.putInt(keyBytes.size)
        buffer.put(keyBytes)
        buffer.put(if (snapshot.isEntitled) 1.toByte() else 0.toByte())
        buffer.putLong(snapshot.confirmedAtMs)
        buffer.putInt(tokenLen)
        if (tokenBytes != null) {
            buffer.put(tokenBytes)
        }
        return buffer.array()
    }
}
