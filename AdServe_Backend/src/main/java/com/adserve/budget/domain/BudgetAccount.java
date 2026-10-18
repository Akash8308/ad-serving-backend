package com.adserve.budget.domain;

import com.adserve.shared.Money;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Executable specification of the ledger invariants:
 *   I-B1  totalSpent + totalReserved <= totalLimit
 *   I-B2  dailySpent + dailyReserved <= dailyLimit (for the current ledger day)
 * The Postgres ledger implements the same rules in single-statement SQL; the shared contract test suite keeps the
 * two in step. Not thread-safe: callers synchronize.
 */
public final class BudgetAccount {
    private BudgetLimits limits;
    private long totalSpent;
    private long totalReserved;
    private LocalDate dailyDay;
    private long dailySpent;
    private long dailyReserved;

    public BudgetAccount(BudgetLimits limits) {
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    /** Non-mutating form of the reservation check. */
    public boolean canReserve(Money amount, LocalDate today) {
        long amt = amount.minor();
        long dailyCommittedToday = today.equals(dailyDay) ? dailySpent + dailyReserved : 0;
        return totalSpent + totalReserved + amt <= limits.total().minor()
                && dailyCommittedToday + amt <= limits.daily().minor();
    }

    /** Lazy day rollover: counters of a previous day are ignored, and reset the moment a new day is written. */
    public boolean tryReserve(Money amount, LocalDate today) {
        if (!canReserve(amount, today)) {
            return false;
        }
        long amt = amount.minor();
        rollTo(today);
        totalReserved += amt;
        dailyReserved += amt;
        return true;
    }

    /** Moves exactly the reserved amount from reserved to spent (I-B4). Daily counters move only if the day still matches. */
    public void settle(Money amount, LocalDate reservationDay) {
        long amt = amount.minor();
        requireReserved(amt);
        totalReserved -= amt;
        totalSpent += amt;
        if (reservationDay.equals(dailyDay)) {
            dailyReserved -= amt;
            dailySpent += amt;
        }
    }

    public void release(Money amount, LocalDate reservationDay) {
        long amt = amount.minor();
        requireReserved(amt);
        totalReserved -= amt;
        if (reservationDay.equals(dailyDay)) {
            dailyReserved -= amt;
        }
    }

    /** @return false (and no change) if the new limits are below what is already committed. */
    public boolean adjustLimits(BudgetLimits newLimits, LocalDate today) {
        if (totalSpent + totalReserved > newLimits.total().minor()) {
            return false;
        }
        long dailyCommittedToday = today.equals(dailyDay) ? dailySpent + dailyReserved : 0;
        if (dailyCommittedToday > newLimits.daily().minor()) {
            return false;
        }
        rollTo(today);
        this.limits = newLimits;
        return true;
    }

    public BudgetView view(LocalDate today) {
        boolean sameDay = today.equals(dailyDay);
        String cur = limits.total().currency();
        return new BudgetView(
                limits.total(), new Money(totalSpent, cur), new Money(totalReserved, cur),
                limits.daily(), new Money(sameDay ? dailySpent : 0, cur), new Money(sameDay ? dailyReserved : 0, cur));
    }

    private void rollTo(LocalDate today) {
        if (!today.equals(dailyDay)) {
            dailyDay = today;
            dailySpent = 0;
            dailyReserved = 0;
        }
    }

    private void requireReserved(long amt) {
        if (totalReserved < amt) {
            throw new IllegalStateException("ledger corruption: releasing more than is reserved");
        }
    }
}
