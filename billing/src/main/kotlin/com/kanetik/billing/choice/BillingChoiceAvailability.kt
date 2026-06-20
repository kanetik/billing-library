package com.kanetik.billing.choice

/**
 * Result of [BillingChoiceActions.isBillingChoiceAvailable] — whether the
 * Billing Choice program is usable for the current user, device, and Play
 * install.
 *
 * Billing Choice is enrollment- and region-gated. For the overwhelming majority
 * of apps and users it is **not** available, and the wrapper reports that as a
 * normal [Unavailable] answer rather than throwing — "not available" is the
 * expected steady state, not an error. Use this as a gate before building any
 * Billing Choice UI: if [Unavailable], skip the whole flow.
 */
@ExperimentalBillingChoiceApi
public sealed class BillingChoiceAvailability {

    /**
     * Billing Choice is available. [choiceScreenType] tells you who renders the
     * selection screen; [externalLinkAvailable] mirrors PBL's
     * `isExternalLinkAvailable`.
     */
    public data class Available(
        public val choiceScreenType: ChoiceScreenType,
        public val externalLinkAvailable: Boolean
    ) : BillingChoiceAvailability()

    /**
     * Billing Choice is not available — the user/region isn't enrolled, the
     * feature isn't supported on this Play install, or the availability query
     * came back non-OK. Treat as "don't show Billing Choice."
     */
    public data object Unavailable : BillingChoiceAvailability()
}
