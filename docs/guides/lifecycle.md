# Lifecycle integration

Wire `BillingConnectionLifecycleManager` to anything observable — an Activity, a Fragment, or `ProcessLifecycleOwner.get()` if you want process-wide warm connection.

```kotlin
class CheckoutActivity : ComponentActivity() {
    private val billing by lazy { BillingRepositoryCreator.create(applicationContext) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycle.addObserver(BillingConnectionLifecycleManager(billing))
    }
}
```

The manager calls `connectToBilling()` on `onStart`, cancels any active collector on `onStop` (the underlying `WhileSubscribed(60_000)` keeps the connection warm for 60s after the last subscriber leaves), and cancels its `SupervisorJob` on `onDestroy`.

For more on the timing, see [Replay semantics](../reference/replay-semantics.md#connection-grace-window).
