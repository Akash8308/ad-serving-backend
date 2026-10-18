package com.adserve.budget.domain;

import com.adserve.shared.DomainRuleViolation;

public final class BudgetAccountNotFound extends DomainRuleViolation {
    public BudgetAccountNotFound(String message) {
        super(message);
    }
}
