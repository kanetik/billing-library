# Logging

Default is silent. Opt into logcat with one line, or wire your own adapter to route through Crashlytics, Timber, or whatever you prefer.

## Silent (default)

```kotlin
val billing = BillingRepositoryCreator.create(context)
```

## Logcat

```kotlin
val billing = BillingRepositoryCreator.create(context, logger = BillingLogger.Android)
```

## Crashlytics adapter

Your code, ~10 lines. The library doesn't dictate a tag — pick one that fits your logging convention (here, `"Billing"`). If you omit `.tag(...)`, Timber falls back to the calling class name; `android.util.Log` requires a tag explicitly.

```kotlin
class CrashlyticsBillingLogger : BillingLogger {
    override fun d(message: String, throwable: Throwable?) {
        Timber.tag("Billing").d(throwable, message)
    }
    override fun w(message: String, throwable: Throwable?) {
        Timber.tag("Billing").w(throwable, message)
    }
    override fun e(message: String, throwable: Throwable?) {
        Timber.tag("Billing").e(throwable, message)
        FirebaseCrashlytics.getInstance().recordException(
            throwable ?: BillingLogException(message)
        )
    }
}
```

```kotlin
val billing = BillingRepositoryCreator.create(context, logger = CrashlyticsBillingLogger())
```

The library does not depend on Timber, Crashlytics, or any logging framework — that wiring is fully on the consumer side.
