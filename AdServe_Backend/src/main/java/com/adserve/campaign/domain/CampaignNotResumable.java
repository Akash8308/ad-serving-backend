package com.adserve.campaign.domain;

import com.adserve.shared.DomainRuleViolation;

public final class CampaignNotResumable extends DomainRuleViolation {
    public CampaignNotResumable(String message) {
        super(message);
    }
}
