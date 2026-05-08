# Replay semantics

`observePurchaseUpdates()` is internally a merge of three channels:

- **Live events** (`OwnedPurchases.Live` and every `FlowOutcome` variant — `Pending`, `Canceled`, `ItemAlreadyOwned`, `ItemUnavailable`, `Failure`, `UnknownResponse`) — `replay = 0`. A re-attached collector (configuration change, `repeatOnLifecycle`, ViewModel recreation) does **not** re-receive the previous live event. The entitlement grant and any one-shot UX (confetti, toasts, analytics) fired exactly once when the event arrived; replaying them on rotation would be a bug.
- **Recovery events** (`OwnedPurchases.Recovered`) — `replay = 1`. A late subscriber (one that attaches after the auto-sweep fired) catches the most recent recovery. This is what makes the recovery feature reliable in patterns where the consumer's collector races the connection coming up. The library tracks tokens passed through `acknowledgePurchase` / `consumePurchase` / `handlePurchase` and filters them out at delivery time (a synchronous `map` reads the current acked-token set per emission); `Recovered` events that filter to empty (or are intrinsically empty) are suppressed entirely. A re-subscribed collector that already handled the recovered purchase does not see the stale snapshot again.
- **Revocation events** (`PurchaseRevoked`) — `replay = 16`, on a dedicated channel separate from the recovery channel. A late subscriber (one that attaches after the consumer's FCM listener pushed one or more `PurchaseRevoked` events via `emitExternalRevocation`) catches up to the last 16 cached revocations. The buffer is sized for the realistic FCM-burst case (multi-product chargebacks resolving simultaneously, or several revocations decoded at process start before the UI is up); larger bursts still cap at 16 — for guaranteed delivery of every event past that, persist on the consumer side before calling `emitExternalRevocation`.

You don't need to dedupe handle / grant / UX for live events — fire confetti directly from the `OwnedPurchases.Live` branch and you'll see it exactly once per purchase, even across rotations. You also don't need to dedupe the `OwnedPurchases.Recovered` branch for `replay = 1` re-emission of already-handled purchases (the library does that). Still treat `Recovered` idempotently if you trigger one-shot UX off it for other state-machine reasons (badge animations, analytics events, etc.).

## Connection grace window

`connectToBilling()` is shared via `SharingStarted.WhileSubscribed(60_000)` — the connection stays alive for 60 seconds after the last subscriber unsubscribes. The 60-second window is what absorbs configuration changes (rotation, theme switch) without churning a fresh `BillingClient` connection on each transition.

If you need different timing, you can wrap the API yourself; a configurable grace window may surface as a creator parameter in a future release if a real consumer asks (see the [Roadmap](../roadmap.md)).
