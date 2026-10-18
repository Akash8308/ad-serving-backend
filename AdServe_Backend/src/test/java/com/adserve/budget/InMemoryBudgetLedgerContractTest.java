package com.adserve.budget;

import com.adserve.budget.domain.BudgetLedger;
import com.adserve.shared.CampaignId;
import com.adserve.testsupport.InMemoryBudgetLedger;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

class InMemoryBudgetLedgerContractTest extends BudgetLedgerContract {
    private final Set<CampaignId> notServable = ConcurrentHashMap.newKeySet();

    @Override
    BudgetLedger newLedger() {
        notServable.clear();
        return new InMemoryBudgetLedger((id, now) -> !notServable.contains(id));
    }

    @Override
    void setServable(CampaignId id, boolean servable) {
        if (servable) {
            notServable.remove(id);
        } else {
            notServable.add(id);
        }
    }
}
