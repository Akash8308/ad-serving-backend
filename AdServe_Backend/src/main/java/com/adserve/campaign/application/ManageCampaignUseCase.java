package com.adserve.campaign.application;

import com.adserve.budget.domain.BudgetBelowCommitted;
import com.adserve.budget.domain.BudgetLedger;
import com.adserve.budget.domain.BudgetView;
import com.adserve.budget.domain.LimitChangeResult;
import com.adserve.campaign.domain.Campaign;
import com.adserve.campaign.domain.CampaignNotFound;
import com.adserve.campaign.domain.CampaignRepository;
import com.adserve.shared.AdvertiserId;
import com.adserve.shared.CampaignId;

import java.time.Clock;
import java.time.Instant;
import java.util.function.Consumer;

/**
 * UC-02 .. UC-07. Each method loads the campaign for its owner (foreign campaigns look missing), applies one domain
 * behavior, saves, then publishes the resulting events. Each method runs inside one database transaction.
 */
public final class ManageCampaignUseCase {
    private final CampaignRepository campaigns;
    private final BudgetLedger ledger;
    private final CampaignEventPublisher events;
    private final Clock clock;

    public ManageCampaignUseCase(CampaignRepository campaigns, BudgetLedger ledger, CampaignEventPublisher events,
                                 Clock clock) {
        this.campaigns = campaigns;
        this.ledger = ledger;
        this.events = events;
        this.clock = clock;
    }

    public CampaignView get(AdvertiserId requester, CampaignId id) {
        Campaign campaign = load(requester, id);
        Instant now = clock.instant();
        BudgetView budget = ledger.snapshot(id, now);
        return new CampaignView(campaign, budget, campaign.servingState(now, budget));
    }

    public Campaign update(UpdateCampaignCommand c) {
        Campaign campaign = load(c.advertiserId(), c.campaignId());
        campaign.update(c.name(), c.bid(), c.schedule(), c.targeting(), c.ads());
        if (c.budget() != null
                && ledger.adjustLimits(c.campaignId(), c.budget(), clock.instant()) == LimitChangeResult.BELOW_COMMITTED) {
            throw new BudgetBelowCommitted("new budget limits are below what is already spent or reserved");
        }
        return saveAndPublish(campaign);
    }

    public Campaign activate(AdvertiserId requester, CampaignId id) {
        return transition(requester, id, campaign -> campaign.activate(clock.instant()));
    }

    public Campaign pause(AdvertiserId requester, CampaignId id) {
        return transition(requester, id, Campaign::pause);
    }

    public Campaign resume(AdvertiserId requester, CampaignId id) {
        Instant now = clock.instant();
        return transition(requester, id, campaign -> campaign.resume(now, ledger.snapshot(id, now)));
    }

    public Campaign complete(AdvertiserId requester, CampaignId id) {
        return transition(requester, id, Campaign::complete);
    }

    private Campaign transition(AdvertiserId requester, CampaignId id, Consumer<Campaign> action) {
        Campaign campaign = load(requester, id);
        action.accept(campaign);
        return saveAndPublish(campaign);
    }

    private Campaign saveAndPublish(Campaign campaign) {
        var pending = campaign.pullEvents();
        Campaign saved = campaigns.save(campaign);
        pending.forEach(events::publish);
        return saved;
    }

    private Campaign load(AdvertiserId requester, CampaignId id) {
        Campaign campaign = campaigns.findById(id).orElseThrow(() -> new CampaignNotFound("campaign not found: " + id));
        campaign.assertOwnedBy(requester);
        return campaign;
    }
}
