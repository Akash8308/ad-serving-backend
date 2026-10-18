package com.adserve.budget.domain;

import com.adserve.shared.CampaignId;
import com.adserve.shared.ReservationId;

import java.time.Instant;

/**
 * The only authority for budget and for "was this ad served". Every operation is atomic.
 * Two implementations pass the same contract suite: in-memory (tests) and PostgreSQL (production).
 */
public interface BudgetLedger {

    /** Creates the account for a new campaign. Called in the same transaction as campaign creation. */
    void open(CampaignId campaignId, BudgetLimits limits);

    /**
     * Atomically checks that the campaign is ACTIVE and inside its schedule (invariant I-B5) and that total and daily
     * funds suffice (I-B1, I-B2), then reserves and records the serve.
     * @throws LedgerUnavailable if the backing store cannot be reached or times out
     */
    ReservationResult reserve(ReservationRequest request);

    /** Charges exactly the reserved amount if the reservation is still RESERVED and inside its window (I-B3, I-B4). */
    SettlementResult settle(ReservationId id, Instant now);

    /** Releases funds of reservations whose window has passed. Returns how many were released. */
    int expireDue(Instant now, int batchSize);

    LimitChangeResult adjustLimits(CampaignId campaignId, BudgetLimits limits, Instant now);

    BudgetView snapshot(CampaignId campaignId, Instant now);
}
