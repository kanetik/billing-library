package com.kanetik.billing

import android.content.Context
import com.kanetik.billing.factory.CoroutinesBillingConnectionFactory
import com.kanetik.billing.logging.BillingLogger

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
 * // or, with logging routed to your own sink:
 * val billing = BillingRepositoryCreator.create(applicationContext, MyTimberLogger())
 * ```
 */
public object BillingRepositoryCreator {

    /**
     * Builds a [BillingRepository] backed by Google Play Billing Library 8.x.
     *
     * @param context Any [Context]; the underlying [com.android.billingclient.api.BillingClient]
     *   will use the application context.
     * @param logger Optional sink for diagnostic messages. Defaults to
     *   [BillingLogger.Noop] — the library is silent unless a logger is supplied.
     */
    public fun create(
        context: Context,
        logger: BillingLogger = BillingLogger.Noop
    ): BillingRepository = EasyBillingRepository(
        billingClientStorage = BillingClientStorage(
            billingFactory = CoroutinesBillingConnectionFactory(context = context),
            logger = logger
        ),
        logger = logger
    )
}
