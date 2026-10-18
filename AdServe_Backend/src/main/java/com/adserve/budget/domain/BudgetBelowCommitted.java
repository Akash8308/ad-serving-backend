package com.adserve.budget.domain;

import com.adserve.shared.DomainRuleViolation;

public final class BudgetBelowCommitted extends DomainRuleViolation {
    public BudgetBelowCommitted(String message) {
        super(message);
    }
}
