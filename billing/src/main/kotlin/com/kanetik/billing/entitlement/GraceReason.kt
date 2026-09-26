package com.kanetik.billing.entitlement

/**
 * Unused: [EntitlementCache] no longer transitions any key into
 * [EntitlementState.InGrace], so this reason is never produced. Retained for
 * source compatibility.
 */
@Deprecated("EntitlementCache no longer produces EntitlementState.InGrace; this type has no effect.")
public enum class GraceReason {
    BillingUnavailable,
    TransientFailure,
}
