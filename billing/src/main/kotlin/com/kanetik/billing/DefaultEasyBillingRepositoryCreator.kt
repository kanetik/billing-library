package com.kanetik.billing

import android.content.Context
import com.kanetik.billing.factory.CoroutinesBillingConnectionFactory

object DefaultEasyBillingRepositoryCreator {

    fun createBillingRepository(applicationContext: Context): BillingRepository = EasyBillingRepository(
        billingClientStorage = BillingClientStorage(
            billingFactory = CoroutinesBillingConnectionFactory(context = applicationContext)
        )
    )
}