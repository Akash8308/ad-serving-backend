package com.adserve.campaign.domain;

import com.adserve.shared.DomainRuleViolation;

public final class InvalidStateTransition extends DomainRuleViolation {
    public InvalidStateTransition(String message) {
        super(message);
    }
}
