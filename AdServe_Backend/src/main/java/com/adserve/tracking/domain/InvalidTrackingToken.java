package com.adserve.tracking.domain;

import com.adserve.shared.DomainRuleViolation;

public final class InvalidTrackingToken extends DomainRuleViolation {
    public InvalidTrackingToken(String message) {
        super(message);
    }
}
