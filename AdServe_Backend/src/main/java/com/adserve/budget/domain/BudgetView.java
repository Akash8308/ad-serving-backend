package com.adserve.budget.domain;

import com.adserve.shared.Money;

/** Read-only snapshot of a ledger account for the current ledger day. */
public record BudgetView(Money totalLimit, Money totalSpent, Money totalReserved,
                         Money dailyLimit, Money dailySpent, Money dailyReserved) {

    public Money remainingTotal() {
        return remaining(totalLimit, totalSpent.plus(totalReserved));
    }

    public Money remainingToday() {
        return remaining(dailyLimit, dailySpent.plus(dailyReserved));
    }

    /** Can the total budget still fund one more reservation of this amount? (used by resume, rule 7) */
    public boolean canAfford(Money amount) {
        return !remainingTotal().isLessThan(amount);
    }

    /** Can today's cap fund it? (a temporary condition, not used to block resume) */
    public boolean canAffordToday(Money amount) {
        return !remainingToday().isLessThan(amount);
    }

    private static Money remaining(Money limit, Money committed) {
        return limit.isLessThan(committed) ? Money.zeroInr() : limit.minus(committed);
    }
}
