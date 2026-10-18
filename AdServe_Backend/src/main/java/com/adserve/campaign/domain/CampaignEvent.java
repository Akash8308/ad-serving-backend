package com.adserve.campaign.domain;

import com.adserve.shared.CampaignId;

/** In-process domain events. Used only to evict serving caches after commit; correctness never depends on them. */
public sealed interface CampaignEvent {
    CampaignId campaignId();

    record CampaignActivated(CampaignId campaignId) implements CampaignEvent { }
    record CampaignPaused(CampaignId campaignId) implements CampaignEvent { }
    record CampaignResumed(CampaignId campaignId) implements CampaignEvent { }
    record CampaignCompleted(CampaignId campaignId) implements CampaignEvent { }
    record CampaignUpdated(CampaignId campaignId) implements CampaignEvent { }
}
