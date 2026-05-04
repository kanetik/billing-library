package com.kanetik.billing.entitlement

/**
 * How long [EntitlementCache] keeps treating a user as entitled after a
 * [com.kanetik.billing.PurchasesUpdate.Failure] event.
 *
 * Two windows because the underlying outages have different shapes:
 *
 *  - [transientFailureMs] — network blips, service disconnects. Typically
 *    minutes; the library already retries with backoff before surfacing the
 *    failure, so the outage you see here has already lasted past the retry
 *    budget. A reasonable default is 1–24 hours depending on how confident
 *    you are in the user's connectivity environment.
 *  - [billingUnavailableMs] — Play Services missing, account ineligibility,
 *    region restrictions. Typically longer-lived; if a user's device can't
 *    talk to billing at all, that often persists across sessions. Common
 *    defaults are 24–72 hours so a user on a flight or in a region with a
 *    bad Play Store install doesn't lose access mid-trip.
 *
 * Both values are in milliseconds. Pass `0` to disable grace for that reason
 * (the cache will transition straight to [EntitlementState.Revoked] on the
 * matching failure type).
 *
 * ## Example
 *
 * ```
 * val policy = GracePolicy(
 *     billingUnavailableMs = TimeUnit.HOURS.toMillis(72), // 3 days
 *     transientFailureMs   = TimeUnit.HOURS.toMillis(6),  // 6 hours
 * )
 * ```
 *
 * @property billingUnavailableMs Grace window in ms when the failure maps to
 *   [GraceReason.BillingUnavailable]. Must be `>= 0`.
 * @property transientFailureMs Grace window in ms when the failure maps to
 *   [GraceReason.TransientFailure]. Must be `>= 0`.
 */
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
         * Disables grace entirely — every [com.kanetik.billing.PurchasesUpdate.Failure]
         * transitions a previously-Granted cache straight to
         * [EntitlementState.Revoked]. Use when you'd rather surface the outage
         * to the user immediately than risk a few extra minutes of premium
         * during an outage.
         */
        public val None: GracePolicy = GracePolicy(
            billingUnavailableMs = 0L,
            transientFailureMs = 0L,
        )
    }
}
