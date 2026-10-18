package com.adserve.testsupport;

import com.adserve.campaign.application.CampaignEventPublisher;
import com.adserve.campaign.domain.CampaignEvent;

import java.util.ArrayList;
import java.util.List;

public final class RecordingEventPublisher implements CampaignEventPublisher {
    public final List<CampaignEvent> published = new ArrayList<>();

    @Override
    public void publish(CampaignEvent event) {
        published.add(event);
    }
}
