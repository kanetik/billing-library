package com.kanetik.billing.entitlement

/**
 * Unused by [EntitlementCache]: [com.kanetik.billing.FlowOutcome.Failure] no
 * longer applies a grace window to existing grants. Retained for source
 * compatibility.
 */
@Deprecated("EntitlementCache no longer applies grace on FlowOutcome.Failure; this type has no effect.")
public data class GracePolicy(
    public val billingUnavailableMs: Long,
    public val transientFailureMs: Long,
) {
    init {
        require(billingUnavailableMs >= 0) {
            "billingUnavailableMs must be >= 0; got $billingUnavailableMs"
        }
        require(transientFailureMs >= 0) {
            "transientFailureMs must be >= 0; got $transientFailureMs"
        }
    }

    /**
     * @return the grace window for [reason] in ms.
     */
    internal fun windowMsFor(reason: GraceReason): Long = when (reason) {
        GraceReason.BillingUnavailable -> billingUnavailableMs
        GraceReason.TransientFailure -> transientFailureMs
    }

    public companion object {
        /**
         * Both windows zeroed. Has no effect on [EntitlementCache] — see the
         * class-level deprecation notice.
         */
        @Suppress("DEPRECATION")
        public val None: GracePolicy = GracePolicy(
            billingUnavailableMs = 0L,
            transientFailureMs = 0L,
        )
    }
}
