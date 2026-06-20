package com.kanetik.billing.choice

/**
 * Which party renders the billing-choice selection screen, as reported by
 * [BillingChoiceAvailability.Available.choiceScreenType].
 *
 * Wraps PBL's `BillingProgramAvailabilityDetails.BillingChoiceAvailabilityDetails.ChoiceScreenType`
 * int constants so the library's public API doesn't pin its ABI to PBL's
 * annotation-int shape (consistent with the rest of the wrapper's result types).
 */
@ExperimentalBillingChoiceApi
public enum class ChoiceScreenType {
    /** PBL `UNSPECIFIED` — no screen type was reported. */
    UNSPECIFIED,

    /** PBL `DEVELOPER_RENDERED` — the app renders its own choice screen. */
    DEVELOPER_RENDERED,

    /** PBL `GOOGLE_RENDERED` — Google Play renders the choice screen. */
    GOOGLE_RENDERED,

    /**
     * A value PBL returned that this wrapper version doesn't recognize — a newer
     * PBL added a screen type after this release. Forward-compatibility bucket so
     * an unknown int never crashes the mapping; treat it conservatively.
     */
    UNKNOWN
}
