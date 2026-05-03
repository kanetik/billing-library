package com.kanetik.billing.exception

/**
 * UI-bucket classification for a [BillingException].
 *
 * Collapses the 13 sealed [BillingException] subtypes into seven categories
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
     * Billing isn't available on this device, account, or for the specific
     * feature the call requested. Includes:
     *  - [BillingException.BillingUnavailableException] — billing API itself
     *    isn't available (Play Services disabled, non-Play distribution
     *    such as some Huawei devices, account not eligible for purchases).
     *  - [BillingException.FeatureNotSupportedException] — the specific
     *    feature isn't supported on this Play Store install (older Play
     *    versions, regional rollout limitations, device capability gaps —
     *    e.g. subscriptions on a device that doesn't have them).
     *
     * Both are runtime device-state conditions, not caller bugs. Terminal —
     * no point retrying. Surface a "this isn't available on your device"
     * message; consider hiding the affected billing UI until availability
     * changes (apps that conditionally enable features via
     * [com.kanetik.billing.BillingActions.isFeatureSupported] usually
     * handle this upstream).
     */
    BillingUnavailable,

    /**
     * The product the user tried to buy isn't configured for sale in their
     * context — [BillingException.ItemUnavailableException]. Common causes:
     * typo in product ID, product not yet activated in Play Console,
     * geo-restriction, account ineligibility for this product. Show a
     * "this product isn't available" message; consider hiding the affected
     * product UI until availability changes.
     */
    ProductUnavailable,

    /**
     * Play and the local cache disagree on ownership state — usually a
     * cross-session race or a stale local view. Includes:
     *  - [BillingException.ItemAlreadyOwnedException] — the user tried to
     *    buy a non-consumable they already own. The right UX is typically
     *    to **restore** the entitlement silently (or with a "you already
     *    own this, restoring..." toast), not to show an error.
     *  - [BillingException.ItemNotOwnedException] — a consume call hit a
     *    purchase Play has no record of (already consumed in another
     *    session, etc.). Typically a no-op for UX; log and move on.
     *
     * Both warrant a re-query of owned purchases (the library's retry loop
     * already does this via [com.kanetik.billing.RetryType.REQUERY_PURCHASE_RETRY])
     * to refresh local state. If that retry's resolution still surfaces
     * the exception, the caller has out-of-band state to reconcile.
     *
     * Bucketed separately from [ProductUnavailable] because the UX is
     * fundamentally different: "you already own this" → restore, "this
     * product isn't for sale" → hide / fallback.
     */
    AlreadyOwned,

    /**
     * The library or app called Play Billing with malformed arguments
     * ([BillingException.DeveloperErrorException]).
     *
     * **This is a bug in your code, not a runtime condition** — PBL is
     * rejecting the call shape. Fix it; should never reach production.
     * Report to crash analytics if it does, and surface a generic
     * "something went wrong" message to the user.
     *
     * (Note: `FeatureNotSupportedException` was previously bucketed here
     * but moved to [BillingUnavailable] — it's a runtime device-state
     * condition, not a caller bug.)
     */
    DeveloperError,

    /**
     * Catch-all for:
     *  - [BillingException.FatalErrorException] — Play's generic `ERROR`
     *    response code.
     *  - [BillingException.UnknownException] — response codes Play
     *    introduced after the library was built.
     *  - [BillingException.WrappedException] — non-Play-Billing throwable
     *    surfaced through [com.kanetik.billing.BillingActions.handlePurchase]
     *    by a custom `BillingActions` implementation (or a fake / test
     *    double). Indicates an implementation-side bug, not a Play
     *    response. Inspect [BillingException.WrappedException.originalCause]
     *    for diagnostics.
     *
     * Use a generic "something went wrong, please try again" message in
     * the UI; report to crash analytics for `WrappedException` since it
     * indicates a code-path bug.
     */
    Other
}
