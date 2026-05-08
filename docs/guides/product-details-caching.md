# Product-details caching

The library does not cache `ProductDetails`. Each `queryProductDetails` call hits Play. If you want session-level caching, wrap the query in a `StateFlow`:

```kotlin
class ProductCache(
    private val billing: BillingRepository,
    scope: CoroutineScope,
) {
    val products: StateFlow<List<ProductDetails>> = flow {
        emit(billing.queryProductDetails(params))
    }.stateIn(scope, SharingStarted.WhileSubscribed(60_000), emptyList())
}
```

This is the recommended pattern. Library-side caching would tax consumers with their own cache (most production apps) and muddle invalidation semantics.
