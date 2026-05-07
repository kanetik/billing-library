package com.kanetik.billing.entitlement.signed

import com.kanetik.billing.entitlement.EntitlementSnapshot
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

/**
 * Internal helper: deterministic byte encoding of an [EntitlementSnapshot]
 * tagged with a schema version, used as the input to HMAC sign / verify.
 *
 * # Wire format (v1)
 * ```
 * canonical_v1 := version_be(4 bytes)
 *              || is_entitled(1 byte: 0 or 1)
 *              || confirmed_at_ms(8 bytes BE long)
 *              || token_len(4 bytes BE int; -1 for null)
 *              || token_utf8(token_len bytes; absent when token_len == -1)
 * ```
 *
 * The version int is part of the bytes the HMAC authenticates (not just a
 * separately-stored prefix), so an attacker can't downgrade a v2 signature
 * to v1 or vice versa without breaking verification.
 *
 * # Adding a field to [EntitlementSnapshot]
 *
 * 1. Add the field to [EntitlementSnapshot] (must be nullable or have a
 *    documented absent-default consistent with v1 reads — additive evolution
 *    only, no breaking shape changes).
 * 2. Bump [CURRENT_VERSION] (e.g., 1 → 2).
 * 3. Add a `2 -> ...` branch to [encode] that writes the new field.
 * 4. Leave the `1 -> ...` branch untouched. It keeps verifying old signatures
 *    by canonical-encoding only the v1-known fields. A snapshot that was
 *    signed at v1 will continue to verify after the library upgrade because
 *    the deserialized snapshot's new field defaults to its absent-value, which
 *    the v1 encoder ignores.
 * 5. Add a roundtrip test: sign at v1, read back through v2 code, verify still
 *    passes. Sign at v2, verify v2.
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
    const val CURRENT_VERSION: Int = 1

    /** Largest version this build of the library knows how to verify. */
    const val MAX_SUPPORTED_VERSION: Int = CURRENT_VERSION

    /**
     * Encodes [snapshot] for signing at the named [version].
     *
     * @throws IllegalArgumentException if [version] is unknown to this build.
     *   Callers verifying a persisted signature should range-check the parsed
     *   version against [MAX_SUPPORTED_VERSION] before invoking and surface a
     *   tamper / unsupported-version event instead of letting this throw.
     */
    fun encode(snapshot: EntitlementSnapshot, version: Int): ByteArray {
        return when (version) {
            1 -> encodeV1(snapshot)
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
}
