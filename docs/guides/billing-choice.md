# Billing Choice (experimental)

Play Billing Library 9.1.0 added **Billing Choice** — a surface for querying whether the Billing Choice program is available to a user and showing Play's information dialog about it. It's part of Google's alternative-billing / user-choice billing rollout.

The wrapper exposes a thin, suspend-style mirror of PBL's surface, behind an opt-in marker.

!!! warning "Experimental — and eligibility-gated"
    This API is **experimental** (`@ExperimentalBillingChoiceApi`) and may change in a future `0.x` release without an ABI-stability guarantee. It ships thin on purpose: the right higher-level shape (e.g. a one-call manager) will be designed once real usage shows what's needed — see [why thin](#why-its-thin).

    Billing Choice is also **enrollment- and region-gated** (EEA, South Korea, India, and others). For most apps and users it is simply **not available**. Calling the query methods when you aren't enrolled is safe — they just report unavailable — but the dialog does nothing useful without enrollment. **Confirm your app is enrolled in the program before building UI around this**, or you'll be wiring up a no-op.

## Opting in

Every Billing Choice method requires opt-in. The requirement is a *warning* (not an error), so it's low-friction to adopt — annotate the call site:

```kotlin
@OptIn(ExperimentalBillingChoiceApi::class)
suspend fun maybeOfferBillingChoice(activity: Activity) {
    // ... calls below
}
```

## Enabling the program on the client

For the underlying `BillingClient` to participate, it must be built with the Billing Choice program enabled. Pass the ready-made factory when you create the repository:

```kotlin
@OptIn(ExperimentalBillingChoiceApi::class)
val billing = BillingRepositoryCreator.create(
    context = applicationContext,
    billingClientFactory = BillingChoiceClientFactory(),
)
```

`BillingChoiceClientFactory` is `DefaultBillingClientFactory`'s setup plus `enableBillingProgram(BillingClient.BillingProgram.BILLING_CHOICE)`. If you already supply a custom factory (you enable user-choice billing or external offers), don't stack this one — add the `enableBillingProgram(...)` call to your own builder so all your program enablements live in one place.

## The flow

The three methods mirror PBL 1:1: **availability → info → dialog**. Gate on availability first; skip everything if it's `Unavailable`.

```kotlin
@OptIn(ExperimentalBillingChoiceApi::class)
suspend fun showBillingChoiceIfAvailable(activity: Activity) {
    // 1. Gate. Unavailable is the normal answer for most apps — not an error.
    when (val availability = billing.isBillingChoiceAvailable()) {
        is BillingChoiceAvailability.Unavailable -> return // skip the whole flow
        is BillingChoiceAvailability.Available -> {
            // availability.choiceScreenType -> who renders the choice screen
            // availability.externalLinkAvailable -> PBL's isExternalLinkAvailable
        }
    }

    // 2. (Optional) Fetch Play-provided display assets to render your own screen.
    val details = billing.getBillingChoiceInfo(
        GetBillingChoiceInfoParams.newBuilder().build()
    )
    // details.imageUrl, details.loyaltyInfo (both nullable)

    // 3. Show Play's information dialog. Main thread — PBL renders from the
    //    activity's window. Throws a BillingException subtype if Play rejects.
    billing.showBillingProgramInformationDialog(
        activity = activity,
        params = BillingProgramInformationDialogParams.newBuilder().build(),
    )
}
```

Behavior notes:

- **`isBillingChoiceAvailable()`** never throws on a non-OK availability response — "not available" is reported as `BillingChoiceAvailability.Unavailable`, because for the vast majority of apps that's the expected steady state, not a failure.
- **`getBillingChoiceInfo(params)`** *does* throw a [`BillingException`](error-handling.md) subtype on a non-OK response — it's an explicit fetch, so a failure is a failure. Both `BillingChoiceDetails` fields are nullable; PBL may return either asset as absent.
- **`showBillingProgramInformationDialog(activity, params)`** must run on the main thread and throws a `BillingException` subtype if Play rejects the call.

All three reuse the wrapper's existing connection management and retry/backoff — same as every other `BillingActions` call.

## Why it's thin

These methods mirror PBL's surface rather than bundling the two-step query→dialog flow into one call. Collapsing it presumes orchestration that hasn't been validated against a real, enrolled app yet — and a wrong abstraction is harder to walk back than a thin one. Shipping thin (behind the experimental marker) lets the higher-level shape emerge from dog-fooding, then promote it without breaking callers. This is the library's standard demand-driven approach (see [Roadmap](../roadmap.md)).

## Learning how Billing Choice works

The wrapper handles the plumbing; the program's *rules* (who's eligible, what the screens must contain, how enrollment works) are Google's, and you'll need their docs to use this in production:

- **[PBL 9.1.0 release notes](https://developer.android.com/google/play/billing/release-notes#9.1.0)** — the canonical entry point for the new APIs and the links into the dedicated Billing Choice integration guide.
- **[Alternative billing (overview)](https://developer.android.com/google/play/billing/alternative)** — the umbrella program Billing Choice sits under, including which markets and app categories qualify.
- **[Alternative billing with user choice — in-app integration](https://developer.android.com/google/play/billing/alternative/alternative-billing-with-user-choice-in-app)** — the user-choice billing integration guide.
- **[UX guidelines for alternative billing](https://developer.android.com/google/play/billing/billing-choice)** — the mandatory information-screen and choice-screen design rules (exact copy, equal visual treatment, etc.).

Enrollment is handled in the Play Console under the alternative billing / user choice program, not in code — start from the release notes above, which link the current enrollment path.
