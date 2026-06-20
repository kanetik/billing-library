package com.kanetik.billing.choice

import android.app.Activity
import androidx.annotation.AnyThread
import androidx.annotation.MainThread
import com.android.billingclient.api.BillingProgramInformationDialogParams
import com.android.billingclient.api.GetBillingChoiceInfoParams
import com.kanetik.billing.exception.BillingException

/**
 * **Experimental.** Suspend-style access to Play Billing's Billing Choice
 * surface (PBL 9.1.0).
 *
 * [com.kanetik.billing.BillingRepository] implements this alongside
 * [com.kanetik.billing.BillingActions]; the methods reuse the same connection
 * management as the rest of the wrapper. Every method here requires opt-in via
 * [ExperimentalBillingChoiceApi].
 *
 * ## What Billing Choice is — and the eligibility gate
 *
 * Billing Choice is part of Google's alternative-billing / user-choice billing
 * rollout: in eligible markets, users can be shown information about, and a
 * choice of, billing programs. It is **enrollment- and region-gated** (EEA,
 * South Korea, India, and others). For most apps and users it is simply not
 * available — [isBillingChoiceAvailable] returns
 * [BillingChoiceAvailability.Unavailable] and you should skip the flow. Calling
 * the query methods when unenrolled is safe (it just reports unavailable);
 * showing the dialog when unenrolled does nothing useful. **Verify your app is
 * enrolled before building UI around this.**
 *
 * For the BillingClient to participate, it must be built with the Billing Choice
 * program enabled — use
 * [BillingChoiceClientFactory][com.kanetik.billing.factory.BillingChoiceClientFactory]
 * (or add `enableBillingProgram(BillingClient.BillingProgram.BILLING_CHOICE)` to
 * your own [com.kanetik.billing.factory.BillingClientFactory]) when constructing
 * the repository.
 *
 * ## Why thin, not a bundled manager
 *
 * These three methods mirror PBL's surface 1:1 (availability → info → dialog)
 * rather than collapsing the flow into one call. The right higher-level shape
 * depends on real usage we don't have yet; shipping thin lets that shape emerge
 * from dog-fooding. See [ExperimentalBillingChoiceApi] for the ABI contract.
 *
 * @see <a href="https://developer.android.com/google/play/billing/release-notes#9.1.0">PBL 9.1.0 release notes</a>
 */
public interface BillingChoiceActions {

    /**
     * Queries whether the Billing Choice program is available for the current
     * user / device / Play install.
     *
     * Wraps PBL `BillingClient.isBillingProgramAvailableAsync(BILLING_CHOICE, …)`.
     * A non-OK availability response (e.g. feature not supported, user not
     * enrolled) is reported as [BillingChoiceAvailability.Unavailable], not
     * thrown — unavailability is the normal answer for the vast majority of
     * apps. Use the result as a gate before calling [getBillingChoiceInfo] or
     * [showBillingProgramInformationDialog].
     */
    @AnyThread
    @ExperimentalBillingChoiceApi
    public suspend fun isBillingChoiceAvailable(): BillingChoiceAvailability

    /**
     * Fetches the Play-provided display assets (image URL, loyalty info) for the
     * Billing Choice program.
     *
     * Wraps PBL `BillingClient.getBillingChoiceInfoAsync(params, …)`. Build
     * [params] with `GetBillingChoiceInfoParams.newBuilder()`. Unlike
     * [isBillingChoiceAvailable], a non-OK response here is surfaced as a thrown
     * [BillingException] subtype — this is an explicit fetch, so a failure is a
     * failure rather than an "unavailable" gate.
     */
    @AnyThread
    @ExperimentalBillingChoiceApi
    public suspend fun getBillingChoiceInfo(params: GetBillingChoiceInfoParams): BillingChoiceDetails

    /**
     * Shows Play's billing-program information dialog over [activity].
     *
     * Wraps PBL `BillingClient.showBillingProgramInformationDialog(activity, params, …)`.
     * Build [params] with `BillingProgramInformationDialogParams.newBuilder()`.
     * Must be called on the main thread (PBL renders the dialog from the calling
     * activity's window). Completes when the dialog reports back; throws a
     * [BillingException] subtype if Play rejects the call.
     */
    @MainThread
    @ExperimentalBillingChoiceApi
    public suspend fun showBillingProgramInformationDialog(
        activity: Activity,
        params: BillingProgramInformationDialogParams
    )
}
