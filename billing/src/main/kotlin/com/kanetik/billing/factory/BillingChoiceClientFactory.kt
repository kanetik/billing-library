package com.kanetik.billing.factory

import android.content.Context
import com.android.billingclient.api.BillingClient
import com.android.billingclient.api.PendingPurchasesParams
import com.android.billingclient.api.PurchasesUpdatedListener
import com.kanetik.billing.choice.ExperimentalBillingChoiceApi

/**
 * **Experimental.** A [BillingClientFactory] that builds a [BillingClient] with
 * the same PBL 9.x recommended setup as [DefaultBillingClientFactory] **plus**
 * the Billing Choice program enabled
 * (`enableBillingProgram(BillingClient.BillingProgram.BILLING_CHOICE)`).
 *
 * Pass it when constructing the repository so the underlying client can
 * participate in Billing Choice:
 *
 * ```
 * @OptIn(ExperimentalBillingChoiceApi::class)
 * val billing = BillingRepositoryCreator.create(
 *     context = applicationContext,
 *     billingClientFactory = BillingChoiceClientFactory(),
 * )
 * ```
 *
 * If you already supply a custom factory (e.g. you enable user-choice billing or
 * external offers), don't use this one — add
 * `enableBillingProgram(BillingClient.BillingProgram.BILLING_CHOICE)` to your own
 * builder instead, so all your program enablements live in one place.
 *
 * Billing Choice is enrollment- and region-gated; enabling the program on a
 * non-enrolled app is harmless (the runtime queries just report unavailable).
 * See [com.kanetik.billing.choice.BillingChoiceActions] for the eligibility
 * caveat.
 */
@ExperimentalBillingChoiceApi
public class BillingChoiceClientFactory : BillingClientFactory {

    override fun createBillingClient(
        context: Context,
        listener: PurchasesUpdatedListener
    ): BillingClient = BillingClient
        .newBuilder(context)
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection()
        .enableBillingProgram(BillingClient.BillingProgram.BILLING_CHOICE)
        .setListener(listener)
        .build()
}
