package com.kanetik.billing.exception

/**
 * UI-bucket classification for a [BillingException].
 *
 * Collapses the 12 sealed [BillingException] subtypes into six categories
 * that map cleanly to user-facing UX. Lets callers maintain a small
 * string-resource map (one per category) instead of branching on every
 * Play Billing response code.
 *
 * Get one via [BillingException.userFacingCategory]. See the [BillingException]
 * class-level KDoc for the recommended usage pattern.
 *
 * The library deliberately does **not** ship localized user-facing strings —
 * tone, voice, and language coverage are app concerns.
 */
public enum class BillingErrorCategory {

    /**
     * User dismissed the purchase flow (back button, cancel tap). Not really
     * an error from the app's perspective. The typical UX response is to do
     * nothing — don't show a dialog, don't log a failure.
     */
    UserCanceled,

    /**
     * Network or Play Store connectivity issue. Includes
     * [BillingException.NetworkErrorException], [BillingException.ServiceDisconnectedException],
     * and [BillingException.ServiceUnavailableException]. Often transient
     * — the library has already retried with backoff before throwing, so
     * surface as "connection problem, please try again."
     */
    Network,

    /**
     * Billing isn't available on this device or account
     * ([BillingException.BillingUnavailableException]). Common causes: Play
     * Services disabled, non-Play distribution (some Huawei devices), account
     * not eligible for purchases. Terminal — no point retrying. Consider
     * hiding billing-related UI entirely until billing becomes available.
     */
    BillingUnavailable,

    /**
     * The product the user tried to buy isn't in a state Play will sell.
     * Includes [BillingException.ItemUnavailableException] (product not
     * configured for this user/region), [BillingException.ItemAlreadyOwnedException]
     * (non-consumable already owned), and [BillingException.ItemNotOwnedException]
     * (consume failed because Play has no record of ownership). For
     * "already owned", the right UX is usually to restore the entitlement
     * silently rather than show an error.
     */
    ProductUnavailable,

    /**
     * The library or app called Play Billing with malformed arguments
     * ([BillingException.DeveloperErrorException]) or the requested feature
     * isn't supported on this Play Store install
     * ([BillingException.FeatureNotSupportedException]).
     *
     * `DeveloperErrorException` is a bug in your code (PBL is rejecting the
     * call shape — fix it; should never reach production). Report to crash
     * analytics if it does.
     *
     * `FeatureNotSupportedException` is a legitimate runtime condition —
     * older Play Store installs, regions where a feature isn't rolled out,
     * or devices that lack a capability (e.g. subscriptions). Surface a
     * "this isn't available on your device" message when you can identify
     * which feature was being checked; otherwise the generic error is fine.
     *
     * Most consumers can render the same generic-error UX for both and
     * report to crash analytics; if your app conditionally enables features
     * via [com.kanetik.billing.BillingActions.isFeatureSupported], you may
     * already be handling `FeatureNotSupportedException` upstream and won't
     * see it leak into purchase flows.
     */
    DeveloperError,

    /**
     * Catch-all for [BillingException.FatalErrorException] (Play's generic
     * ERROR response code) and [BillingException.UnknownException] (response
     * codes Play introduced after the library was built). Use a generic
     * "something went wrong, please try again" message.
     */
    Other
}
