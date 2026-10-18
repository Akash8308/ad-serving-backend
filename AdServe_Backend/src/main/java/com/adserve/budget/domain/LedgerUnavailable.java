package com.adserve.budget.domain;

import com.adserve.shared.DomainRuleViolation;

public final class LedgerUnavailable extends DomainRuleViolation {
    public LedgerUnavailable(String message) {
        super(message);
    }
}
