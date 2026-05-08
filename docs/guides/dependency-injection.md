# Dependency injection

## Hilt

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object BillingModule {
    @Provides
    @Singleton
    fun provideBillingLogger(): BillingLogger = BillingLogger.Android  // or your own adapter

    @Provides
    @Singleton
    fun provideBillingRepository(
        @ApplicationContext context: Context,
        logger: BillingLogger,
    ): BillingRepository = BillingRepositoryCreator.create(
        context = context,
        logger = logger,
    )
}
```

## Koin

```kotlin
val billingModule = module {
    single<BillingLogger> { BillingLogger.Android }
    single<BillingRepository> {
        BillingRepositoryCreator.create(
            context = androidContext(),
            logger = get(),
        )
    }
}
```

## Manual wiring

If you're not using a DI framework, hold a single instance in your `Application`:

```kotlin
class MyApplication : Application() {
    val billing: BillingRepository by lazy {
        BillingRepositoryCreator.create(
            context = applicationContext,
            logger = BillingLogger.Android,
        )
    }
}
```

Then access it via `(application as MyApplication).billing` from your Activity / ViewModel. The repository is safe to share across the process — its internal `connectToBilling()` flow uses `WhileSubscribed(60s)` to multiplex the underlying `BillingClient` across all consumers.

## Narrow interfaces

`BillingRepository` is composed of three narrower interfaces (`BillingActions`, `BillingConnector`, `BillingPurchaseUpdatesOwner`). Depend on the narrowest one your code under test needs and bind it from the same singleton. This keeps test mocks tight — mocking `BillingActions` for a checkout ViewModel is far less work than mocking the full repo.
