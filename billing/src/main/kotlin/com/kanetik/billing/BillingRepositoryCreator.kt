package com.kanetik.billing

import android.content.Context
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.kanetik.billing.factory.BillingClientFactory
import com.kanetik.billing.factory.CoroutinesBillingConnectionFactory
import com.kanetik.billing.factory.DefaultBillingClientFactory
import com.kanetik.billing.logging.BillingLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

/**
 * Public entry point for constructing a [BillingRepository].
 *
 * Most consumers only ever call [create] and inject the returned
 * [BillingRepository] (or one of its narrower interfaces — [BillingActions],
 * [BillingConnector], [BillingPurchaseUpdatesOwner]) wherever billing logic is
 * needed.
 *
 * ```
 * val billing = BillingRepositoryCreator.create(applicationContext)
 *
 * // With logging routed to your own sink:
 * val billing = BillingRepositoryCreator.create(applicationContext, MyTimberLogger())
 *
 * // With a custom BillingClient builder (e.g. enabling user-choice billing):
 * val billing = BillingRepositoryCreator.create(
 *     context = applicationContext,
 *     billingClientFactory = MyCustomClientFactory(),
 * )
 *
 * // For unit tests — supply your own dispatchers + scope so nothing depends on
 * // ProcessLifecycleOwner or the real Main dispatcher:
 * val billing = BillingRepositoryCreator.create(
 *     context = applicationContext,
 *     scope = TestScope(),
 *     ioDispatcher = StandardTestDispatcher(),
 *     uiDispatcher = StandardTestDispatcher(),
 * )
 * ```
 */
public object BillingRepositoryCreator {

    /**
     * Builds a [BillingRepository] backed by Google Play Billing Library 8.x.
     *
     * @param context Any [Context]; passed to the underlying [com.android.billingclient.api.BillingClient]
     *   builder. Application context is fine.
     * @param logger Sink for diagnostic messages. Defaults to [BillingLogger.Noop]
     *   — the library is silent unless a logger is supplied.
     * @param billingClientFactory Strategy for constructing the
     *   [com.android.billingclient.api.BillingClient]. Defaults to
     *   [DefaultBillingClientFactory], which builds a client with the
     *   PBL 8.x recommended setup. Provide a custom impl to tweak the
     *   builder (e.g. enable user-choice billing, swap configuration in
     *   tests).
     * @param scope The [CoroutineScope] used to share the billing connection across
     *   collectors. Defaults to [ProcessLifecycleOwner.get].lifecycleScope, which
     *   keeps the connection alive for the application's lifecycle. Override this
     *   in tests (the default crashes outside an Android process where
     *   `ProcessLifecycleOwner` isn't initialized).
     * @param ioDispatcher Dispatcher used for non-UI billing operations and the
     *   internal retry/backoff loop. Defaults to [Dispatchers.IO].
     * @param uiDispatcher Dispatcher used by `launchFlow` only — Play Billing
     *   requires `launchBillingFlow` to run on the main thread. Defaults to
     *   [Dispatchers.Main].
     */
    public fun create(
        context: Context,
        logger: BillingLogger = BillingLogger.Noop,
        billingClientFactory: BillingClientFactory = DefaultBillingClientFactory(),
        scope: CoroutineScope = ProcessLifecycleOwner.get().lifecycleScope,
        ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
        uiDispatcher: CoroutineDispatcher = Dispatchers.Main
    ): BillingRepository = DefaultBillingRepository(
        billingClientStorage = BillingClientStorage(
            billingFactory = CoroutinesBillingConnectionFactory(
                context = context,
                billingClientFactory = billingClientFactory
            ),
            logger = logger,
            connectionShareScope = scope
        ),
        logger = logger,
        ioDispatcher = ioDispatcher,
        uiDispatcher = uiDispatcher
    )
}
