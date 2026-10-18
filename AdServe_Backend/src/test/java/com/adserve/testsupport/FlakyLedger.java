package com.adserve.testsupport;

import com.adserve.budget.domain.BudgetLedger;
import com.adserve.budget.domain.BudgetLimits;
import com.adserve.budget.domain.BudgetView;
import com.adserve.budget.domain.LedgerUnavailable;
import com.adserve.budget.domain.LimitChangeResult;
import com.adserve.budget.domain.ReservationRequest;
import com.adserve.budget.domain.ReservationResult;
import com.adserve.budget.domain.SettlementResult;
import com.adserve.shared.CampaignId;
import com.adserve.shared.ReservationId;

import java.time.Instant;

/** Failure injection at the dependency boundary: reserve() throws LedgerUnavailable while "down". */
public final class FlakyLedger implements BudgetLedger {
    private final BudgetLedger delegate;
    public volatile boolean down;

    public FlakyLedger(BudgetLedger delegate) {
        this.delegate = delegate;
    }

    @Override public void open(CampaignId id, BudgetLimits limits) { delegate.open(id, limits); }

    @Override
    public ReservationResult reserve(ReservationRequest request) {
        if (down) {
            throw new LedgerUnavailable("simulated outage");
        }
        return delegate.reserve(request);
    }

    @Override public SettlementResult settle(ReservationId id, Instant now) { return delegate.settle(id, now); }
    @Override public int expireDue(Instant now, int batchSize) { return delegate.expireDue(now, batchSize); }
    @Override public LimitChangeResult adjustLimits(CampaignId id, BudgetLimits l, Instant now) { return delegate.adjustLimits(id, l, now); }
    @Override public BudgetView snapshot(CampaignId id, Instant now) { return delegate.snapshot(id, now); }
}
