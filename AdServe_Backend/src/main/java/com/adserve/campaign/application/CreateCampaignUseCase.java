package com.adserve.campaign.application;

import com.adserve.budget.domain.BudgetLedger;
import com.adserve.campaign.domain.Campaign;
import com.adserve.campaign.domain.CampaignRepository;

/**
 * UC-01. Creates the campaign and its budget account. Must run inside ONE database transaction (the Spring adapter
 * wraps it with @Transactional); creation is the only place two aggregates are written together.
 */
public final class CreateCampaignUseCase {
    private final CampaignRepository campaigns;
    private final BudgetLedger ledger;

    public CreateCampaignUseCase(CampaignRepository campaigns, BudgetLedger ledger) {
        this.campaigns = campaigns;
        this.ledger = ledger;
    }

    public Campaign create(CreateCampaignCommand command) {
        Campaign campaign = Campaign.create(command.advertiserId(), command.name(), command.bid(),
                command.schedule(), command.targeting(), command.ads());
        Campaign saved = campaigns.save(campaign);
        ledger.open(saved.id(), command.budget());
        return saved;
    }
}
