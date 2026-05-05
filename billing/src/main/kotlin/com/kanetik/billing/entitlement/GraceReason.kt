package com.kanetik.billing.entitlement

/**
 * Why [EntitlementCache] entered an [EntitlementState.InGrace] state.
 *
 * Mapped from the underlying [com.kanetik.billing.exception.BillingException]
 * carried by a [com.kanetik.billing.FlowOutcome.Failure] event:
 *
 *  - [BillingUnavailable] — the billing service itself is unavailable on this
 *    device (Play Services missing, account ineligible, region restriction,
 *    feature not supported). These outages are typically longer-lived than
 *    transient network blips, so [GracePolicy] exposes them as a separate
 *    knob — apps may want a longer grace window before yanking premium for
 *    "Play Store isn't working" vs. "user just lost wifi".
 *  - [TransientFailure] — anything else: classification uses the
 *    [com.kanetik.billing.exception.BillingErrorCategory] from
 *    [com.kanetik.billing.exception.BillingException.userFacingCategory],
 *    not the raw `RetryType`. Anything not categorised as `BillingUnavailable`
 *    falls into this bucket — including network errors, service
 *    disconnections, generic billing errors, AND terminal failures like
 *    `DeveloperError` or `FatalError`. The cache treats all of those as
 *    "give the user a short grace window before yanking premium"; consumers
 *    that want fatal errors to revoke immediately should pass
 *    [GracePolicy.None] (or set `transientFailureMs = 0`).
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
     * Underlying [com.kanetik.billing.exception.BillingException] is in any
     * [com.kanetik.billing.exception.BillingErrorCategory] except
     * `BillingUnavailable`. Includes transient outages (network error,
     * service disconnect) AND terminal failures (developer error, fatal
     * error) — the cache uniformly applies a grace window before revoking,
     * because reading "no entitlement" is the wrong default for an actively
     * paying user even when the cause is a developer-side bug.
     * Uses [GracePolicy.transientFailureMs] for the grace window.
     */
    TransientFailure,
}
