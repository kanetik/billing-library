package com.kanetik.billing.choice

/**
 * Result of [BillingChoiceActions.getBillingChoiceInfo] — the display assets PBL
 * returns for a billing program.
 *
 * Wraps PBL's `BillingChoiceInfo` (currently just an image URL + a loyalty-info
 * string) so the library's public API doesn't pin its ABI to PBL's holder
 * shape. Both fields are nullable — PBL may return either as absent.
 *
 * @property imageUrl PBL `getPlayBillingChoiceImageUrl()` — URL of the
 *   Play-provided image to render on your choice screen, or `null` if none.
 * @property loyaltyInfo PBL `getPlayBillingLoyaltyInfo()` — loyalty messaging to
 *   surface alongside the Play billing option, or `null` if none.
 */
@ExperimentalBillingChoiceApi
public data class BillingChoiceDetails(
    public val imageUrl: String?,
    public val loyaltyInfo: String?
)
