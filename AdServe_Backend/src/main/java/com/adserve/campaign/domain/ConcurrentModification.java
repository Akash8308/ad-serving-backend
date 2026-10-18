package com.adserve.campaign.domain;

import com.adserve.shared.DomainRuleViolation;

public final class ConcurrentModification extends DomainRuleViolation {
    public ConcurrentModification(String message) {
        super(message);
    }
}
