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

    @Test
    fun `encode is deterministic for the same snapshot and version`() {
        val a = SnapshotCanonicalBytes.encode(baseline, 1)
        val b = SnapshotCanonicalBytes.encode(baseline, 1)
        assertThat(a).isEqualTo(b)
    }

    @Test
    fun `encode starts with the version int in big-endian`() {
        val bytes = SnapshotCanonicalBytes.encode(baseline, 1)
        val parsedVersion = ByteBuffer.wrap(bytes, 0, 4).int
        assertThat(parsedVersion).isEqualTo(1)
    }

    @Test
    fun `different isEntitled produces different bytes`() {
        val truthy = SnapshotCanonicalBytes.encode(baseline, 1)
        val falsy = SnapshotCanonicalBytes.encode(baseline.copy(isEntitled = false), 1)
        assertThat(truthy).isNotEqualTo(falsy)
    }

    @Test
    fun `different confirmedAtMs produces different bytes`() {
        val original = SnapshotCanonicalBytes.encode(baseline, 1)
        val shifted = SnapshotCanonicalBytes.encode(baseline.copy(confirmedAtMs = baseline.confirmedAtMs + 1), 1)
        assertThat(original).isNotEqualTo(shifted)
    }

    @Test
    fun `different purchaseToken produces different bytes`() {
        val original = SnapshotCanonicalBytes.encode(baseline, 1)
        val swapped = SnapshotCanonicalBytes.encode(baseline.copy(purchaseToken = "tok-other"), 1)
        assertThat(original).isNotEqualTo(swapped)
    }

    @Test
    fun `null and empty purchaseToken produce different bytes`() {
        val nullToken = SnapshotCanonicalBytes.encode(baseline.copy(purchaseToken = null), 1)
        val emptyToken = SnapshotCanonicalBytes.encode(baseline.copy(purchaseToken = ""), 1)
        assertThat(nullToken).isNotEqualTo(emptyToken)
    }

    @Test
    fun `null token sets token_len field to -1`() {
        val bytes = SnapshotCanonicalBytes.encode(baseline.copy(purchaseToken = null), 1)
        // version(4) + isEntitled(1) + confirmedAtMs(8) = 13, then token_len(4) at offset 13
        val tokenLen = ByteBuffer.wrap(bytes, 13, 4).int
        assertThat(tokenLen).isEqualTo(-1)
    }

    @Test
    fun `empty token sets token_len field to 0`() {
        val bytes = SnapshotCanonicalBytes.encode(baseline.copy(purchaseToken = ""), 1)
        val tokenLen = ByteBuffer.wrap(bytes, 13, 4).int
        assertThat(tokenLen).isEqualTo(0)
    }

    @Test
    fun `unknown version throws IllegalArgumentException`() {
        try {
            SnapshotCanonicalBytes.encode(baseline, 999)
            error("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertThat(e.message).contains("999")
        }
    }
}
