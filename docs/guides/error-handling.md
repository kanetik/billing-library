# Error handling

PBL hands you back errors as integer response codes on a `BillingResult`. The codes have very different stakes. A `NETWORK_ERROR` is a "try again in a moment". `BILLING_UNAVAILABLE` *looks* like "hide the IAP UI entirely", but it's ambiguous — Play returns it both for a genuinely unsupported device (Play Store missing) **and** for a momentary hiccup (Play Store mid-update, account still syncing right after install), so it's unsafe to gate an irreversible decision on it directly (use [`queryBillingAvailability()`](#deterministic-availability-for-irreversible-decisions) for that). `ITEM_ALREADY_OWNED` is closer to a success-with-restore than an error. `DEVELOPER_ERROR` means you wrote the API call wrong and retrying won't help. Naive code treats them all the same and either hammers Play with retries or shows a generic "something went wrong" dialog for everything.

The library does two things about that:

1. Maps each response code to a typed `BillingException` subtype with a `RetryType` hint, and runs the retries that should happen automatically. Network errors back off exponentially, transient disconnects retry quickly, and ownership-mismatches re-query owned state and try once more. What reaches your `catch` is what didn't recover.
2. Categorizes every exception into a `BillingErrorCategory` enum (seven buckets: `UserCanceled`, `Network`, `BillingUnavailable`, `ProductUnavailable`, `AlreadyOwned`, `DeveloperError`, `Other`) so your UI can localize per bucket without memorizing twelve response codes.

Most `BillingActions` methods that fail throw a typed `BillingException` subtype: `queryPurchases`, `queryProductDetails`, `queryProductDetailsWithUnfetched`, `consumePurchase`, `acknowledgePurchase`, `launchFlow`, `showInAppMessages`, `isFeatureSupported`. The high-level `handlePurchase` helper is the exception: it returns a sealed `HandlePurchaseResult` with a `Failure(BillingException)` variant instead (see [Handling `handlePurchase` failures correctly](#handling-handlepurchase-failures-correctly) below). The library's retry loop already retries transient failures (`SIMPLE_RETRY`, `EXPONENTIAL_RETRY`, `REQUERY_PURCHASE_RETRY`) up to three times with appropriate backoff before throwing, so what reaches your `catch` (or your `Failure` branch for `handlePurchase`) is whatever didn't recover. `launchFlow` runs once with no retry, because UI-initiated purchases shouldn't silently retry behind the user.

For UI handling, branch on `userFacingCategory` (see [Showing errors to users](#showing-errors-to-users) below). For lower-level branching, use the sealed subtype directly:

```kotlin
try {
    billing.queryProductDetails(params)
} catch (e: BillingException) {
    when (e) {
        is BillingException.NetworkErrorException,
        is BillingException.ServiceUnavailableException,
        is BillingException.ServiceDisconnectedException -> showRetryUI()
        is BillingException.BillingUnavailableException -> hideBillingFeatures()
        is BillingException.ItemUnavailableException -> showSoldOut()
        else -> reportToCrashlytics(e)
    }
}
```

`BillingException` subtypes:

| Subtype | When | Internal RetryType |
|---|---|---|
| `NetworkErrorException` | Lower-level network failure | `EXPONENTIAL_RETRY` |
| `ServiceUnavailableException` | Play Store service unreachable | `EXPONENTIAL_RETRY` |
| `ServiceDisconnectedException` | Client connection dropped mid-call | `SIMPLE_RETRY` |
| `FatalErrorException` | Generic Play Billing `ERROR` response code | `EXPONENTIAL_RETRY` |
| `ItemAlreadyOwnedException` | One-time product already owned | `REQUERY_PURCHASE_RETRY` |
| `ItemNotOwnedException` | Trying to consume something not in inventory | `REQUERY_PURCHASE_RETRY` |
| `BillingUnavailableException` | `BILLING_UNAVAILABLE` — Play Store missing/disabled **or** transiently unavailable (the code is ambiguous; see [deterministic availability](#deterministic-availability-for-irreversible-decisions)) | `NONE` |
| `ItemUnavailableException` | Product not configured for this user/country | `NONE` |
| `DeveloperErrorException` | API misuse — fix the code | `NONE` |
| `FeatureNotSupportedException` | Feature missing on this Play version | `NONE` |
| `UserCanceledException` | User dismissed the purchase flow | `NONE` |
| `UnknownException` | Response code PBL doesn't document — log it | `NONE` |
| `WrappedException` | Non-PBL throwable wrapped by `handlePurchase` (NPE, `IllegalStateException` from a custom `BillingActions` impl, `AssertionError` from a fake, etc.) or surfaced while establishing the connection (e.g. a custom `BillingClientFactory` throwing). Distinct from `UnknownException`; carries `originalCause` for diagnostics. | `NONE` |

`RetryType` is exposed on every exception via `e.retryType`, but you usually don't need to consult it directly: the library has already retried before throwing. The hint is there for diagnostics and for callers wanting to render "we'll try again automatically" messaging on the early throw paths.

## Connection-setup retry

The retry loop above covers `BillingActions` calls. Establishing the underlying connection gets the same treatment: a transient `BillingClient.startConnection` failure — `SERVICE_DISCONNECTED` (`SIMPLE_RETRY`) or `SERVICE_UNAVAILABLE` / `NETWORK_ERROR` (`EXPONENTIAL_RETRY`) — is retried internally (bounded attempts + backoff) before `BillingConnector.connectToBilling()` emits a terminal `BillingConnectionResult.Error`. So a `connectToBilling()` `Error` means the transient condition was actually *persistent*, not a one-off blip — which is the point at which it's worth acting on. Don't re-derive "is this transient?" by pattern-matching the exception subtype and response code on the consumer side; the library already did, retried, and only surfaced what didn't recover.

Tune or disable this via the `connectionRetryPolicy` parameter on `BillingRepositoryCreator.create`:

```kotlin
// Defaults (4 attempts; 500ms fixed for SIMPLE_RETRY, 2s×2 backoff for EXPONENTIAL_RETRY):
val billing = BillingRepositoryCreator.create(context)

// Custom budget:
val billing = BillingRepositoryCreator.create(
    context = context,
    connectionRetryPolicy = ConnectionRetryPolicy(maxAttempts = 6, simpleRetryBackoffMillis = 250L),
)

// Opt out — surface the first transient connection failure immediately
// (e.g. if you run your own connection-retry layer):
val billing = BillingRepositoryCreator.create(
    context = context,
    connectionRetryPolicy = ConnectionRetryPolicy.None,
)
```

Failures the library classifies as non-transient for connection-retry purposes (`BILLING_UNAVAILABLE`, `DEVELOPER_ERROR`, `FEATURE_NOT_SUPPORTED`, etc.) are *not* retried at the connection layer — they surface immediately, since an in-loop retry won't flip them. Note this is a statement about *connection retry*, not about terminality for your own gating: `BILLING_UNAVAILABLE` in particular stays ambiguous (it can be a transient blip), so don't promote "surfaced immediately" into "this device can't pay" — use [`queryBillingAvailability()`](#deterministic-availability-for-irreversible-decisions) for irreversible decisions.

## Deterministic availability for irreversible decisions

`connectToBilling()` is the right signal for "is billing *working right now*" — but it can't cleanly answer "can this device *ever* do Play Billing", because PBL collapses two very different situations into one `BILLING_UNAVAILABLE` (code 3) response: a genuinely unsupported device (no Play Store) and a merely transient blip (Play Store mid-update, account still syncing right after install). Gating an **irreversible** decision on that code — granting a free fallback tier to "can't pay" users, permanently hiding a purchase CTA — mis-fires on a single first-launch hiccup and never recovers.

`BillingConnector.queryBillingAvailability(): BillingAvailability` disambiguates them deterministically. It's a `suspend` function — call it from a coroutine (it bounds its own connection attempt, so it won't suspend indefinitely):

```kotlin
// e.g. in a ViewModel: viewModelScope.launch { gateOnAvailability() }
suspend fun gateOnAvailability() {
    when (billing.queryBillingAvailability()) {
        BillingAvailability.UNAVAILABLE -> grantNoPlayFallback()   // terminal — safe to act on
        BillingAvailability.AVAILABLE   -> showPurchaseUi()
        BillingAvailability.UNKNOWN     -> showNeutralLoadingState() // retry later; do NOT decide
    }
}
```

It resolves in two steps:

1. **Offline Play Store check first.** If the Play Store package (`com.android.vending`) is absent or disabled — a stable, offline fact — the verdict is the terminal `UNAVAILABLE`. This is the *only* path to `UNAVAILABLE`; a transient code 3 never produces it.
2. **Bounded connection attempt** (8s) only when the Play Store is present. Success → `AVAILABLE`; any failure (including a transient code 3) or a timeout → `UNKNOWN`.

Treat the three verdicts by their *terminality*, not their optimism:

- **`UNAVAILABLE`** is the only verdict safe to gate an irreversible decision on.
- **`UNKNOWN`** must never be treated as terminal — keep the user in a neutral/loading state and re-query later.
- **`AVAILABLE`** is advisory: it confirms billing is usable now, not a permanent guarantee.

!!! warning "Package visibility (Android 11+ / targetSdk 30+)"
    Step 1 relies on querying `com.android.vending`. The library's manifest declares the required `<queries>` entry, which merges into your app at build time — **no action needed if you consume the AAR normally.** If you strip merged manifest entries (aggressive manifest tooling, an unusual merge setup), re-add it, or a present-but-hidden Play Store will be misread as absent and yield a false `UNAVAILABLE`.

!!! note "Implementing a custom `BillingConnector`"
    `queryBillingAvailability()` is a **defaulted** interface method returning `UNKNOWN`, so existing hand-rolled `BillingConnector` / `BillingRepository` fakes keep compiling. The real repository from `BillingRepositoryCreator.create(...)` overrides it with the full two-step resolution above.

## Showing errors to users

**Never display `BillingException.message` in your UI.** It's a debug-context dump (class name, response code, sub-response, debug message) intended for logs, Crashlytics, and dashboards. Showing it leaks internal Play strings like `ServiceDisconnectedException` and `BILLING_RESPONSE_CODE_3` into your dialogs.

For UI, branch on `BillingException.userFacingCategory` (returns a `BillingErrorCategory` — seven buckets: `UserCanceled`, `Network`, `BillingUnavailable`, `ProductUnavailable`, `AlreadyOwned`, `DeveloperError`, `Other`) and localize per bucket from your own string resources:

```kotlin
catch (e: BillingException) {
    when (e.userFacingCategory) {
        BillingErrorCategory.UserCanceled -> return  // not really an error
        BillingErrorCategory.AlreadyOwned -> restoreEntitlement()  // restore, don't error
        BillingErrorCategory.Network -> showError(getString(R.string.purchase_error_network))
        BillingErrorCategory.BillingUnavailable -> showError(getString(R.string.purchase_error_billing_unavailable))
        BillingErrorCategory.ProductUnavailable -> showError(getString(R.string.purchase_error_product_unavailable))
        BillingErrorCategory.DeveloperError,
        BillingErrorCategory.Other -> showError(getString(R.string.purchase_error_generic))
    }
    log.e("Billing failure", e)  // .message is fine here — it's a log
}
```

The library doesn't ship localized user-facing strings; tone, voice, and language coverage are app concerns.

## Handling `handlePurchase` failures correctly

`handlePurchase` returns a sealed `HandlePurchaseResult`: `Success`, `AlreadyAcknowledged`, `NotPurchased`, `NotOwned`, or `Failure(exception)`. The compiler nudges you to branch on each. **Grant entitlement on `Success` and `AlreadyAcknowledged` (both safe), nothing else.** Play auto-refunds the unacknowledged purchase within ~3 days and the paid-for feature silently evaporates if you grant on a `Failure` and the underlying ack call doesn't recover.

```kotlin
when (val r = billing.handlePurchase(purchase, consume = false)) {
    HandlePurchaseResult.Success -> grantEntitlement()
    HandlePurchaseResult.AlreadyAcknowledged -> grantEntitlement() // no PBL call made; safe
    HandlePurchaseResult.NotPurchased -> {} // pending — wait for terminal state
    HandlePurchaseResult.NotOwned -> {
        // Play says this purchase isn't owned anymore (stale snapshot,
        // refund, parallel consume). Don't grant; defer to grace/revoke
        // logic and consider re-querying owned purchases.
    }
    is HandlePurchaseResult.Failure -> showError(r.exception.userFacingCategory)
    // do NOT grant on Failure — the recovery sweep retries on next connect
}
```

The `AlreadyAcknowledged` variant fires when `consume = false` and the `Purchase` already has `isAcknowledged = true`; the library short-circuits before reaching out to Play. Treat it as entitlement-equivalent to `Success`. It exists as a separate variant so consumers can distinguish "we just acked" from "it was already done" for logging / metrics, and so `Failure` no longer overlaps with `Failure(DeveloperErrorException)` from a redundant acknowledge call. **Consumers can now safely untrack-on-Failure for retry on the next recovery sweep**: `Failure` unambiguously means a transient or terminal ack failure worth retrying, never an "already-acked, this will fail forever" loop or an "ownership-mismatch, recovery can't help" loop. (Those cases surface as `AlreadyAcknowledged` and `NotOwned` respectively.) The `consume = true` path does not produce `AlreadyAcknowledged`: consumables aren't acked, they're consumed, and Play doesn't expose an `isConsumed` field on `Purchase` for a parallel check.

The `NotOwned` variant fires when Play replies `ITEM_NOT_OWNED` from the underlying acknowledge / consume call after the library's `RetryType.REQUERY_PURCHASE_RETRY` budget is exhausted. Semantically distinct from `Failure`: ownership disagrees with the input (typically a stale `queryPurchases` snapshot), and retrying the ack against a non-owned purchase keeps returning `ITEM_NOT_OWNED`, so recovery sweeps can't help. Don't grant; defer to your grace / revoke logic and consider re-querying owned purchases. Previously this surfaced as `Failure(BillingException.ItemNotOwnedException)`, forcing consumers to reach into the exception subclass hierarchy to distinguish ownership-mismatch from transient ack-call failures.

The auto-recovery sweep (see [Purchase recovery](purchase-recovery.md)) re-emits the unacknowledged purchase on the next successful connection, so a transient `Failure` is recoverable; a granted-then-refunded purchase is not.

Lower-level `consumePurchase` and `acknowledgePurchase` still throw `BillingException` directly. Callers at that layer are already in the weeds, and a thrown exception is appropriate there. `handlePurchase` is the high-level helper that gets the typed-result treatment because forgetting the failure case is the most common bug.
