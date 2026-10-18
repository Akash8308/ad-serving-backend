package com.adserve.campaign.domain;

import com.adserve.shared.DomainRuleViolation;

public final class CampaignNotFound extends DomainRuleViolation {
    public CampaignNotFound(String message) {
        super(message);
    }
}
