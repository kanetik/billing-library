package com.kanetik.billing.entitlement

/**
 * Why [EntitlementCache] entered an [EntitlementState.InGrace] state.
 *
 * Mapped from the underlying [com.kanetik.billing.exception.BillingException]
 * carried by a [com.kanetik.billing.PurchasesUpdate.Failure] event:
 *
 *  - [BillingUnavailable] — the billing service itself is unavailable on this
 *    device (Play Services missing, account ineligible, region restriction,
 *    feature not supported). These outages are typically longer-lived than
 *    transient network blips, so [GracePolicy] exposes them as a separate
 *    knob — apps may want a longer grace window before yanking premium for
 *    "Play Store isn't working" vs. "user just lost wifi".
 *  - [TransientFailure] — anything else with a non-`NONE`
 *    [com.kanetik.billing.RetryType] (network errors, service disconnections,
 *    generic billing errors). Short outages; the library has already retried
 *    with backoff before surfacing.
 */
public enum class GraceReason {

    /**
     * Underlying [com.kanetik.billing.exception.BillingException] is in the
     * [com.kanetik.billing.exception.BillingErrorCategory.BillingUnavailable]
     * UI bucket — billing service or feature isn't available on this device /
     * account. Uses [GracePolicy.billingUnavailableMs] for the grace window.
     */
    BillingUnavailable,

    /**
     * Underlying [com.kanetik.billing.exception.BillingException] indicates a
     * transient outage (network error, service disconnect, generic error).
     * Uses [GracePolicy.transientFailureMs] for the grace window.
     */
    TransientFailure,
}
