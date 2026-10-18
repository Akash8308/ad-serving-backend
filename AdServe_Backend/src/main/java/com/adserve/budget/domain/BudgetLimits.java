package com.adserve.budget.domain;

import com.adserve.shared.Money;
import com.adserve.shared.ValidationException;

import java.util.Objects;

public record BudgetLimits(Money total, Money daily) {
    public BudgetLimits {
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(daily, "daily");
        if (!total.isPositive() || !daily.isPositive()) {
            throw new ValidationException("budget limits must be positive");
        }
        if (daily.isGreaterThan(total)) {
            throw new ValidationException("daily budget cannot exceed total budget");
        }
    }
}
