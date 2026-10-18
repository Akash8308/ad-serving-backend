package com.adserve.campaign.application;

import com.adserve.campaign.domain.CampaignEvent;

/** In-process publication, delivered after commit by the infrastructure adapter. Best-effort by design. */
public interface CampaignEventPublisher {
    void publish(CampaignEvent event);
}
