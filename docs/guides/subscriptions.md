# Subscriptions (v0.1.x)

Subscriptions are a deeper rabbit hole than one-time purchases. A single product can carry multiple base plans, each with its own offers (free trials, intro pricing, regional discounts). Replacements (upgrades, downgrades, or add-ons) happen as multi-line-item flows. Prepaid plans defer billing. And every replacement purchase carries a `linkedPurchaseToken` field that, if your code misses it, double-grants entitlement on every plan change — the new purchase looks identical to a fresh buy unless you parse the JSON and check.

Most apps that ship subscriptions without working through these either over-grant (free upgrades for everyone who taps the wrong button) or strand users on stale plans.

The v0.1.x series supports subscriptions at the *protocol* level: `queryPurchases`, `queryProductDetails`, and `launchFlow` all accept `BillingClient.ProductType.SUBS`. So you can buy, observe, and acknowledge subscriptions today — the offer-selection and replacement-mode helpers that make the dev ergonomics tolerable are what's missing. Typed helpers land in v0.2.0; this guide documents the manual path until then.

What's missing in v0.1.x:

- Subscription offer-token selection helpers.
- Multi-line-item replacement helpers (`SubscriptionProductReplacementParams`).
- A subs sample in `/sample`.
- Subs-flavored docs.

Planned for v0.2.0 — see the [Roadmap](../roadmap.md) for the full list of subs work pending. If you ship subscriptions on v0.1.x, you write the offer-token + replacement logic directly with PBL APIs; the rest of the library (connection, retry, error mapping, lifecycle, logging) still applies.

## Subscription replacement: parsing `linkedPurchaseToken` yourself

A subscription upgrade/downgrade/crossgrade purchase carries a non-null `linkedPurchaseToken` pointing at the prior subscription. Treating that as a fresh grant double-grants entitlement on plan changes. PBL's `Purchase` API doesn't expose a getter for `linkedPurchaseToken` (`AccountIdentifiers` only carries `obfuscatedAccountId` / `obfuscatedProfileId`); the field is only present in `purchase.originalJson`. Until the v0.2.0 typed `SubscriptionReplacement` variant ships, parse it yourself:

```kotlin
fun Purchase.linkedPurchaseToken(): String? = try {
    org.json.JSONObject(originalJson)
        .optString("linkedPurchaseToken")
        .takeIf { it.isNotEmpty() }
} catch (e: org.json.JSONException) { null }
```

Treat a non-null result as a plan change (invalidate the old token, grant against the new one) rather than a fresh purchase. IAP-only apps are unaffected — one-time products never carry a `linkedPurchaseToken`.
