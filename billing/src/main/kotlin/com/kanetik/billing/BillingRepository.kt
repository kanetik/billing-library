package com.kanetik.billing

/**
 * The full Kanetik Billing surface: querying products / launching flows /
 * acknowledging / consuming ([BillingActions]), observing purchase updates
 * ([BillingPurchaseUpdatesOwner]), and managing the underlying Play Billing
 * connection ([BillingConnector]).
 *
 * Most consumers inject the full [BillingRepository] in one place and depend
 * on the narrower interface(s) they actually use everywhere else. Obtain an
 * instance via [BillingRepositoryCreator.create].
 */
public interface BillingRepository : BillingActions, BillingPurchaseUpdatesOwner, BillingConnector
