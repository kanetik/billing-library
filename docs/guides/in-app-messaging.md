# In-app messaging

Surface Play Billing's transactional messaging UI to prompt users to fix failed payment methods (typical for subscriptions, also reachable for IAP):

```kotlin
val result = billing.showInAppMessages(
    activity = this,
    params = InAppMessageParams.newBuilder()
        .addInAppMessageCategoryToShow(
            InAppMessageParams.InAppMessageCategoryId.TRANSACTIONAL
        )
        .build()
)
when (result) {
    is BillingInAppMessageResult.NoActionNeeded -> {} // common path
    is BillingInAppMessageResult.SubscriptionStatusUpdated -> {
        // The user fixed their payment method; refresh entitlement.
        refreshFromBackend(result.purchaseToken)
    }
}
```

The sealed wrapper means PBL's `InAppMessageResult` shape doesn't leak into your call sites or pin our ABI.
