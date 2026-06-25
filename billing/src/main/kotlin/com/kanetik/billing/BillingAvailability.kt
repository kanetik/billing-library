package com.kanetik.billing

/**
 * Whether Google Play Billing can be used on this device + install, as a
 * **deterministic, terminal-vs-transient–aware** verdict.
 *
 * This exists because the raw Play response code `BILLING_UNAVAILABLE` (3) is
 * ambiguous: it is returned both when the device genuinely cannot ever do Play
 * Billing (no Play Store, restricted profile) **and** when billing is merely
 * momentarily unavailable (Play Store mid-update, Google account still syncing
 * right after install, Play Services updating). Treating a transient code 3 as
 * a permanent "this device can't pay" verdict is a well-known footgun — a
 * consumer that, say, grants a free ad-free tier to "can't pay" devices will
 * mis-grant a normal user on a single unlucky first-launch hiccup and then
 * never show them the upsell.
 *
 * [queryBillingAvailability][BillingConnector.queryBillingAvailability]
 * disambiguates by checking the Play Store package itself (presence + enabled
 * state — an offline, synchronous fact) before falling back to a connection
 * attempt:
 *  - Play Store absent/disabled → [UNAVAILABLE] (deterministic, safe to treat
 *    as terminal).
 *  - Play Store present + connection succeeds → [AVAILABLE].
 *  - Play Store present + connection fails (including a transient code 3) →
 *    [UNKNOWN] — **do not** treat as terminal; retry later.
 */
public enum class BillingAvailability {
    /**
     * Play Billing is usable: the Play Store package is present and enabled and
     * a connection has succeeded. Safe to show purchase UI.
     */
    AVAILABLE,

    /**
     * Play Billing is **deterministically** unusable on this device: the Play
     * Store package (`com.android.vending`) is not installed or is disabled.
     * This is a stable, offline fact — safe to treat as terminal (e.g. to gate
     * a no-Play fallback). It will not flip to [AVAILABLE] without the user
     * installing/enabling the Play Store, at which point a fresh query reflects
     * it.
     */
    UNAVAILABLE,

    /**
     * Indeterminate: the Play Store package is present, but a connection has not
     * (yet) succeeded — a transient failure, a slow/cold connection, or a
     * momentary `BILLING_UNAVAILABLE`. **Callers must not treat this as
     * terminal.** Keep the user in a neutral/loading state and re-query later;
     * do not make an irreversible decision (free grant, hiding the upsell) on
     * [UNKNOWN].
     */
    UNKNOWN,
}
