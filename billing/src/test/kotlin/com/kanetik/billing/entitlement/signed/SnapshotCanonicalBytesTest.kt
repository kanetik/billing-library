package com.kanetik.billing.entitlement.signed

import com.google.common.truth.Truth.assertThat
import com.kanetik.billing.entitlement.EntitlementSnapshot
import org.junit.Test
import java.nio.ByteBuffer

class SnapshotCanonicalBytesTest {

    private val baseline = EntitlementSnapshot(
        isEntitled = true,
        confirmedAtMs = 1_700_000_000_000L,
        purchaseToken = "tok-baseline",
    )

    private val sampleKey = "entitlement_one"

    // -- v1 (legacy, no entitlement key) ----------------------------------

    @Test
    fun `v1 encode is deterministic for the same snapshot`() {
        val a = SnapshotCanonicalBytes.encode(baseline, sampleKey, 1)
        val b = SnapshotCanonicalBytes.encode(baseline, sampleKey, 1)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `v1 encode ignores entitlement key (legacy single-entitlement format)`() {
        val a = SnapshotCanonicalBytes.encode(baseline, "one", 1)
        val b = SnapshotCanonicalBytes.encode(baseline, "two", 1)
        // v1 was written by the single-entitlement library and does NOT
        // include the entitlement key in the canonical bytes. The same
        // snapshot bytes are produced regardless of the entitlement key —
        // which is what lets v0.1.x v1 sigs continue to verify on upgrade.
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `v1 encode starts with the version int in big-endian`() {
        val bytes = SnapshotCanonicalBytes.encode(baseline, sampleKey, 1)
        val parsedVersion = ByteBuffer.wrap(bytes, 0, 4).int
        assertThat(parsedVersion).isEqualTo(1)
    }

    @Test
    fun `v1 different isEntitled produces different bytes`() {
        val truthy = SnapshotCanonicalBytes.encode(baseline, sampleKey, 1)
        val falsy = SnapshotCanonicalBytes.encode(baseline.copy(isEntitled = false), sampleKey, 1)
        assertThat(truthy).isNotEqualTo(falsy)
    }

    @Test
    fun `v1 different confirmedAtMs produces different bytes`() {
        val original = SnapshotCanonicalBytes.encode(baseline, sampleKey, 1)
        val shifted = SnapshotCanonicalBytes.encode(baseline.copy(confirmedAtMs = baseline.confirmedAtMs + 1), sampleKey, 1)
        assertThat(original).isNotEqualTo(shifted)
    }

    @Test
    fun `v1 different purchaseToken produces different bytes`() {
        val original = SnapshotCanonicalBytes.encode(baseline, sampleKey, 1)
        val swapped = SnapshotCanonicalBytes.encode(baseline.copy(purchaseToken = "tok-other"), sampleKey, 1)
        assertThat(original).isNotEqualTo(swapped)
    }

    @Test
    fun `v1 null and empty purchaseToken produce different bytes`() {
        val nullToken = SnapshotCanonicalBytes.encode(baseline.copy(purchaseToken = null), sampleKey, 1)
        val emptyToken = SnapshotCanonicalBytes.encode(baseline.copy(purchaseToken = ""), sampleKey, 1)
        assertThat(nullToken).isNotEqualTo(emptyToken)
    }

    @Test
    fun `v1 null token sets token_len field to -1`() {
        val bytes = SnapshotCanonicalBytes.encode(baseline.copy(purchaseToken = null), sampleKey, 1)
        // v1 layout: version(4) + isEntitled(1) + confirmedAtMs(8) = 13, then token_len(4) at offset 13.
        val tokenLen = ByteBuffer.wrap(bytes, 13, 4).int
        assertThat(tokenLen).isEqualTo(-1)
    }

    @Test
    fun `v1 empty token sets token_len field to 0`() {
        val bytes = SnapshotCanonicalBytes.encode(baseline.copy(purchaseToken = ""), sampleKey, 1)
        val tokenLen = ByteBuffer.wrap(bytes, 13, 4).int
        assertThat(tokenLen).isEqualTo(0)
    }

    // -- v2 (current, includes entitlement key) ---------------------------

    @Test
    fun `v2 encode is deterministic for the same snapshot and key`() {
        val a = SnapshotCanonicalBytes.encode(baseline, sampleKey, 2)
        val b = SnapshotCanonicalBytes.encode(baseline, sampleKey, 2)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `v2 different entitlement keys produce different bytes (cross-key swap detection)`() {
        val a = SnapshotCanonicalBytes.encode(baseline, "one", 2)
        val b = SnapshotCanonicalBytes.encode(baseline, "two", 2)
        assertThat(a).isNotEqualTo(b)
    }

    @Test
    fun `v2 encode starts with the version int in big-endian`() {
        val bytes = SnapshotCanonicalBytes.encode(baseline, sampleKey, 2)
        assertThat(ByteBuffer.wrap(bytes, 0, 4).int).isEqualTo(2)
    }

    @Test
    fun `v2 different isEntitled produces different bytes`() {
        val truthy = SnapshotCanonicalBytes.encode(baseline, sampleKey, 2)
        val falsy = SnapshotCanonicalBytes.encode(baseline.copy(isEntitled = false), sampleKey, 2)
        assertThat(truthy).isNotEqualTo(falsy)
    }

    @Test
    fun `v2 different confirmedAtMs produces different bytes`() {
        val original = SnapshotCanonicalBytes.encode(baseline, sampleKey, 2)
        val shifted = SnapshotCanonicalBytes.encode(baseline.copy(confirmedAtMs = baseline.confirmedAtMs + 1), sampleKey, 2)
        assertThat(original).isNotEqualTo(shifted)
    }

    @Test
    fun `v2 different purchaseToken produces different bytes`() {
        val original = SnapshotCanonicalBytes.encode(baseline, sampleKey, 2)
        val swapped = SnapshotCanonicalBytes.encode(baseline.copy(purchaseToken = "tok-other"), sampleKey, 2)
        assertThat(original).isNotEqualTo(swapped)
    }

    @Test
    fun `v2 includes key bytes in the canonical encoding`() {
        val bytes = SnapshotCanonicalBytes.encode(baseline, "abc", 2)
        // v2 layout: version(4) + key_len(4) + key_utf8(key_len) + ...
        val keyLen = ByteBuffer.wrap(bytes, 4, 4).int
        assertThat(keyLen).isEqualTo(3)
        val keyBytes = bytes.copyOfRange(8, 8 + keyLen)
        assertThat(String(keyBytes, Charsets.UTF_8)).isEqualTo("abc")
    }

    @Test
    fun `v2 supports empty key`() {
        // K = Unit + keyToStorageId = { "" } collapses to an empty key. Must
        // still produce deterministic distinct-from-other-bytes output.
        val emptyKey = SnapshotCanonicalBytes.encode(baseline, "", 2)
        val nonEmpty = SnapshotCanonicalBytes.encode(baseline, "x", 2)
        assertThat(emptyKey).isNotEqualTo(nonEmpty)

        // key_len = 0 immediately followed by the rest of the v2 fields.
        val keyLen = ByteBuffer.wrap(emptyKey, 4, 4).int
        assertThat(keyLen).isEqualTo(0)
    }

    // -- version validation ----------------------------------------------

    @Test
    fun `unknown version throws IllegalArgumentException`() {
        try {
            SnapshotCanonicalBytes.encode(baseline, sampleKey, 999)
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("999")
        }
    }

    @Test
    fun `CURRENT_VERSION is v2`() {
        assertThat(SnapshotCanonicalBytes.CURRENT_VERSION).isEqualTo(2)
    }
}
