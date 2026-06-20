package com.kanetik.billing.choice

/**
 * Opt-in marker for the **experimental** Billing Choice surface
 * ([BillingChoiceActions] and its result types).
 *
 * Billing Choice was added in Play Billing Library 9.1.0 and is part of
 * Google's alternative-billing / user-choice billing rollout — a region-gated,
 * enrollment-gated program (EEA, South Korea, India, and others). Because the
 * library author has not yet validated the surface against a real, enrolled
 * app, the wrapper deliberately ships it as a *thin* mirror of PBL's two-step
 * query → dialog flow rather than a higher-level abstraction. The marker exists
 * so that shape can evolve — including into a bundled manager if dog-fooding
 * shows the two-step flow is a footgun — **without an ABI break**.
 *
 * Opt in at the call site:
 *
 * ```
 * @OptIn(ExperimentalBillingChoiceApi::class)
 * suspend fun maybeShowBillingChoice(activity: Activity) { /* ... */ }
 * ```
 *
 * The requirement is a [warning][RequiresOptIn.Level.WARNING], not an error —
 * the feature is meant to be used (dog-fooded), just with eyes open that the
 * shape may change in a future 0.x release.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.WARNING,
    message = "Billing Choice support is experimental and may change in a future 0.x release. " +
        "Opt in with @OptIn(ExperimentalBillingChoiceApi::class)."
)
@Retention(AnnotationRetention.BINARY)
@Target(
    AnnotationTarget.CLASS,
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY
)
public annotation class ExperimentalBillingChoiceApi
