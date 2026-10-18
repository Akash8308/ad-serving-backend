package com.adserve.budget;

import com.adserve.budget.domain.BudgetAccount;
import com.adserve.budget.domain.BudgetView;
import com.adserve.shared.Money;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static com.adserve.testsupport.TestData.limits;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BudgetAccountTest {
    private static final LocalDate D1 = LocalDate.parse("2026-11-02");
    private static final LocalDate D2 = LocalDate.parse("2026-11-03");

    @Test
    void reservesUpToTheLimitAndNotBeyond() {
        BudgetAccount a = new BudgetAccount(limits(1000, 1000));
        assertTrue(a.tryReserve(Money.inr(600), D1));
        assertFalse(a.tryReserve(Money.inr(401), D1));
        assertTrue(a.tryReserve(Money.inr(400), D1));
        assertFalse(a.tryReserve(Money.inr(1), D1));
        BudgetView v = a.view(D1);
        assertEquals(1000, v.totalReserved().minor());
        assertEquals(0, v.remainingTotal().minor());
    }

    @Test
    void failedReservationChangesNothing() {
        BudgetAccount a = new BudgetAccount(limits(1000, 500));
        assertFalse(a.tryReserve(Money.inr(501), D1));
        BudgetView v = a.view(D1);
        assertEquals(0, v.totalReserved().minor());
        assertEquals(0, v.dailyReserved().minor());
    }

    @Test
    void dailyCapAppliesPerDayAndRollsOverLazily() {
        BudgetAccount a = new BudgetAccount(limits(10_000, 500));
        assertTrue(a.tryReserve(Money.inr(500), D1));
        assertFalse(a.tryReserve(Money.inr(1), D1));
        assertTrue(a.tryReserve(Money.inr(500), D2));
        assertEquals(500, a.view(D2).dailyReserved().minor());
        assertEquals(1000, a.view(D2).totalReserved().minor());
    }

    @Test
    void settleMovesReservedToSpent() {
        BudgetAccount a = new BudgetAccount(limits(1000, 1000));
        a.tryReserve(Money.inr(300), D1);
        a.settle(Money.inr(300), D1);
        BudgetView v = a.view(D1);
        assertEquals(0, v.totalReserved().minor());
        assertEquals(300, v.totalSpent().minor());
        assertEquals(300, v.dailySpent().minor());
    }

    @Test
    void settlingAnEarlierDaysReservationLeavesTodaysDailyCountersAlone() {
        BudgetAccount a = new BudgetAccount(limits(10_000, 1000));
        a.tryReserve(Money.inr(400), D1);
        a.tryReserve(Money.inr(100), D2);
        a.settle(Money.inr(400), D1);
        BudgetView v = a.view(D2);
        assertEquals(400, v.totalSpent().minor());
        assertEquals(100, v.totalReserved().minor());
        assertEquals(0, v.dailySpent().minor());
        assertEquals(100, v.dailyReserved().minor());
    }

    @Test
    void releaseFreesFundsAgain() {
        BudgetAccount a = new BudgetAccount(limits(1000, 1000));
        a.tryReserve(Money.inr(1000), D1);
        a.release(Money.inr(1000), D1);
        assertTrue(a.tryReserve(Money.inr(1000), D1));
    }

    @Test
    void releasingMoreThanReservedIsALedgerBug() {
        BudgetAccount a = new BudgetAccount(limits(1000, 1000));
        assertThrows(IllegalStateException.class, () -> a.release(Money.inr(1), D1));
    }

    @Test
    void limitsCannotDropBelowCommittedFunds() {
        BudgetAccount a = new BudgetAccount(limits(1000, 1000));
        a.tryReserve(Money.inr(600), D1);
        assertFalse(a.adjustLimits(limits(500, 500), D1));
        assertTrue(a.adjustLimits(limits(600, 600), D1));
        assertTrue(a.adjustLimits(limits(5000, 1000), D1));
        assertEquals(5000, a.view(D1).totalLimit().minor());
    }
}
