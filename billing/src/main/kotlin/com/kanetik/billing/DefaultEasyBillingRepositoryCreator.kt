package com.kanetik.billing

import android.content.Context
import com.kanetik.billing.factory.CoroutinesBillingConnectionFactory
import com.kanetik.billing.logging.BillingLogger

object DefaultEasyBillingRepositoryCreator {

    fun createBillingRepository(
        applicationContext: Context,
        logger: BillingLogger = BillingLogger.Noop
    ): BillingRepository = EasyBillingRepository(
        billingClientStorage = BillingClientStorage(
            billingFactory = CoroutinesBillingConnectionFactory(context = applicationContext),
            logger = logger
        ),
        logger = logger
    )
}
