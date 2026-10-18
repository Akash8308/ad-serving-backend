package com.adserve.budget;

import com.adserve.budget.domain.AdSnapshot;
import com.adserve.budget.domain.BudgetLedger;
import com.adserve.budget.domain.BudgetView;
import com.adserve.budget.domain.LimitChangeResult;
import com.adserve.budget.domain.Reservation;
import com.adserve.budget.domain.ReservationRequest;
import com.adserve.budget.domain.ReservationResult;
import com.adserve.budget.domain.ReservationStatus;
import com.adserve.budget.domain.SettlementResult;
import com.adserve.shared.AdId;
import com.adserve.shared.CampaignId;
import com.adserve.shared.Money;
import com.adserve.shared.ReservationId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static com.adserve.testsupport.TestData.NOW;
import static com.adserve.testsupport.TestData.PUBLISHER;
import static com.adserve.testsupport.TestData.limits;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behavioral contract every BudgetLedger implementation must satisfy. Run against the in-memory ledger now and against
 * the PostgreSQL ledger (Testcontainers) in the persistence phase: same tests, same expectations.
 */
abstract class BudgetLedgerContract {
    static final Duration TTL = Duration.ofMinutes(10);

    protected BudgetLedger ledger;

    /** Fresh, empty ledger. */
    abstract BudgetLedger newLedger();

    /** Controls the campaign gate (ACTIVE and inside schedule). Campaigns are servable unless marked otherwise. */
    abstract void setServable(CampaignId id, boolean servable);

    @BeforeEach
    void setUpLedger() {
        ledger = newLedger();
    }

    // ---- helpers ----

    CampaignId open(long total, long daily) {
        CampaignId id = CampaignId.newId();
        ledger.open(id, limits(total, daily));
        return id;
    }

    ReservationRequest request(CampaignId id, String requestId, long amount, Instant now) {
        AdSnapshot ad = new AdSnapshot(AdId.newId(), "headline", "https://c.example.com/a.png", "https://e.example.com");
        return new ReservationRequest(ReservationId.newId(), id, PUBLISHER, requestId, Money.inr(amount), ad, now, TTL);
    }

    Reservation reserved(CampaignId id, String requestId, long amount, Instant now) {
        ReservationResult r = ledger.reserve(request(id, requestId, amount, now));
        return assertInstanceOf(ReservationResult.Reserved.class, r).reservation();
    }

    BudgetView view(CampaignId id, Instant now) {
        return ledger.snapshot(id, now);
    }

    // ---- BUD-01 ----
    @Test
    void insufficientFundsAreRejectedAndNothingIsWritten() {
        CampaignId id = open(1000, 1000);
        assertInstanceOf(ReservationResult.Rejected.class, ledger.reserve(request(id, "r1", 1001, NOW)));
        assertEquals(0, view(id, NOW).totalReserved().minor());
    }

    // BUD-02
    @Test
    void exactRemainingAmountCanBeReservedOnceThenNothingMore() {
        CampaignId id = open(1000, 1000);
        reserved(id, "r1", 600, NOW);
        reserved(id, "r2", 400, NOW);
        assertInstanceOf(ReservationResult.Rejected.class, ledger.reserve(request(id, "r3", 1, NOW)));
        assertEquals(1000, view(id, NOW).totalReserved().minor());
    }

    // BUD-03
    @Test
    void dailyCapIsEnforcedAndResetsOnTheNextUtcDay() {
        CampaignId id = open(10_000, 500);
        reserved(id, "r1", 500, NOW);
        assertInstanceOf(ReservationResult.Rejected.class, ledger.reserve(request(id, "r2", 1, NOW)));
        Instant tomorrow = NOW.plus(Duration.ofDays(1));
        reserved(id, "r3", 500, tomorrow);
        assertEquals(500, view(id, tomorrow).dailyReserved().minor());
    }

    // BUD-04 (R-02 regression: the gate lives inside the reservation itself)
    @Test
    void reservationIsRejectedWhenTheCampaignIsNotServable() {
        CampaignId id = open(1000, 1000);
        setServable(id, false);
        assertInstanceOf(ReservationResult.Rejected.class, ledger.reserve(request(id, "r1", 100, NOW)));
        assertEquals(0, view(id, NOW).totalReserved().minor());
        setServable(id, true);
        reserved(id, "r2", 100, NOW);
    }

    // BUD-05
    @Test
    void settlementChargesExactlyTheReservedAmount() {
        CampaignId id = open(1000, 1000);
        Reservation res = reserved(id, "r1", 300, NOW);
        SettlementResult s = ledger.settle(res.id(), NOW.plusSeconds(30));
        assertInstanceOf(SettlementResult.Settled.class, s);
        BudgetView v = view(id, NOW);
        assertEquals(300, v.totalSpent().minor());
        assertEquals(0, v.totalReserved().minor());
        assertEquals(700, v.remainingTotal().minor());
    }

    // BUD-06
    @Test
    void secondSettlementChargesNothing() {
        CampaignId id = open(1000, 1000);
        Reservation res = reserved(id, "r1", 300, NOW);
        ledger.settle(res.id(), NOW.plusSeconds(1));
        assertInstanceOf(SettlementResult.AlreadySettled.class, ledger.settle(res.id(), NOW.plusSeconds(2)));
        assertEquals(300, view(id, NOW).totalSpent().minor());
    }

    // BUD-07
    @Test
    void settlementAfterTheWindowIsRejectedEvenIfNotYetSwept() {
        CampaignId id = open(1000, 1000);
        Reservation res = reserved(id, "r1", 300, NOW);
        SettlementResult late = ledger.settle(res.id(), NOW.plus(TTL));
        assertEquals(new SettlementResult.NotBillable(SettlementResult.Reason.WINDOW_EXPIRED), late);
        assertEquals(0, view(id, NOW).totalSpent().minor());
    }

    @Test
    void settlementAfterTheSweepIsRejectedToo() {
        CampaignId id = open(1000, 1000);
        Reservation res = reserved(id, "r1", 300, NOW);
        assertEquals(1, ledger.expireDue(NOW.plus(TTL), 100));
        assertEquals(new SettlementResult.NotBillable(SettlementResult.Reason.WINDOW_EXPIRED),
                ledger.settle(res.id(), NOW.plus(TTL).minusSeconds(1)));
        assertEquals(0, view(id, NOW).totalSpent().minor());
    }

    @Test
    void settlingAnUnknownReservationIsNotBillable() {
        assertEquals(new SettlementResult.NotBillable(SettlementResult.Reason.UNKNOWN),
                ledger.settle(ReservationId.newId(), NOW));
    }

    // BUD-08
    @Test
    void expirySweepFreesFundsOnlyForDueReservations() {
        CampaignId id = open(1000, 1000);
        reserved(id, "early", 400, NOW);
        reserved(id, "late", 400, NOW.plus(Duration.ofMinutes(5)));
        assertEquals(0, ledger.expireDue(NOW.plus(Duration.ofMinutes(9)), 100));
        assertEquals(1, ledger.expireDue(NOW.plus(TTL), 100));
        assertEquals(400, view(id, NOW.plus(TTL)).totalReserved().minor());
        reserved(id, "again", 600, NOW.plus(TTL));
    }

    @Test
    void expirySweepRespectsTheBatchSize() {
        CampaignId id = open(10_000, 10_000);
        for (int i = 0; i < 5; i++) {
            reserved(id, "r" + i, 100, NOW);
        }
        assertEquals(2, ledger.expireDue(NOW.plus(TTL), 2));
        assertEquals(3, ledger.expireDue(NOW.plus(TTL), 100));
        assertEquals(0, view(id, NOW).totalReserved().minor());
    }

    // BUD-09
    @Test
    void settlingYesterdaysReservationDoesNotTouchTodaysDailyCounters() {
        CampaignId id = open(10_000, 1000);
        Reservation yesterday = reserved(id, "y", 400, NOW);
        Instant tomorrow = NOW.plus(Duration.ofDays(1));
        reserved(id, "t", 100, tomorrow);
        assertInstanceOf(SettlementResult.Settled.class, ledger.settle(yesterday.id(), NOW.plusSeconds(5)));
        BudgetView v = view(id, tomorrow);
        assertEquals(400, v.totalSpent().minor());
        assertEquals(100, v.totalReserved().minor());
        assertEquals(100, v.dailyReserved().minor());
        assertEquals(0, v.dailySpent().minor());
    }

    // BUD-10
    @Test
    void limitsCannotBeLoweredBelowCommittedFundsButCanBeRaised() {
        CampaignId id = open(1000, 1000);
        reserved(id, "r1", 600, NOW);
        assertEquals(LimitChangeResult.BELOW_COMMITTED, ledger.adjustLimits(id, limits(500, 500), NOW));
        assertEquals(1000, view(id, NOW).totalLimit().minor());
        assertEquals(LimitChangeResult.CHANGED, ledger.adjustLimits(id, limits(2000, 1000), NOW));
        assertEquals(2000, view(id, NOW).totalLimit().minor());
    }

    // SRV-11 at ledger level
    @Test
    void duplicateServeRequestReturnsTheOriginalAndReservesOnce() {
        CampaignId id = open(1000, 1000);
        Reservation first = reserved(id, "same-request", 300, NOW);
        ReservationResult second = ledger.reserve(request(id, "same-request", 300, NOW.plusSeconds(2)));
        ReservationResult.Duplicate dup = assertInstanceOf(ReservationResult.Duplicate.class, second);
        assertEquals(first.id(), dup.existing().id());
        assertEquals(300, view(id, NOW).totalReserved().minor());
    }

    @Test
    void reservationsKeepTheServedAdSnapshot() {
        CampaignId id = open(1000, 1000);
        ReservationRequest req = request(id, "r1", 100, NOW);
        Reservation res = assertInstanceOf(ReservationResult.Reserved.class, ledger.reserve(req)).reservation();
        assertEquals(req.ad(), res.ad());
        assertEquals(ReservationStatus.RESERVED, res.status());
        assertEquals(NOW.plus(TTL), res.expiresAt());
    }

    // BUD-11: 200 parallel reservations against funds for exactly 50 (R-01 / R-24 regression)
    @Test
    void parallelReservationsNeverOverspend() throws Exception {
        CampaignId id = open(5000, 5000);
        int attempts = 200;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        try {
            List<Callable<ReservationResult>> jobs = new ArrayList<>();
            for (int i = 0; i < attempts; i++) {
                String requestId = "par-" + i;
                jobs.add(() -> ledger.reserve(request(id, requestId, 100, NOW)));
            }
            int reservedCount = 0;
            for (Future<ReservationResult> f : pool.invokeAll(jobs)) {
                if (f.get() instanceof ReservationResult.Reserved) {
                    reservedCount++;
                }
            }
            assertEquals(50, reservedCount);
            BudgetView v = view(id, NOW);
            assertEquals(5000, v.totalReserved().minor());
            assertEquals(0, v.remainingTotal().minor());
        } finally {
            pool.shutdownNow();
        }
    }

    // BUD-12: clicks racing the expiry sweeper; every reservation ends settled XOR expired, counters stay consistent
    @Test
    void settlementRacingTheSweeperChargesOnlyWhatWasSettled() throws Exception {
        int n = 200;
        CampaignId id = open(1_000_000, 1_000_000);
        List<Reservation> all = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            all.add(reserved(id, "race-" + i, 10, NOW));
        }
        Instant beforeExpiry = NOW.plus(TTL).minusSeconds(1);
        Instant atExpiry = NOW.plus(TTL);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Callable<Boolean>> jobs = new ArrayList<>();
            for (Reservation r : all) {
                jobs.add(() -> ledger.settle(r.id(), beforeExpiry) instanceof SettlementResult.Settled);
            }
            for (int i = 0; i < 8; i++) {
                jobs.add(() -> {
                    ledger.expireDue(atExpiry, 25);
                    return false;
                });
            }
            int settled = 0;
            for (Future<Boolean> f : pool.invokeAll(jobs)) {
                if (f.get()) {
                    settled++;
                }
            }
            ledger.expireDue(atExpiry, 1000);
            BudgetView v = view(id, NOW);
            assertEquals(0, v.totalReserved().minor());
            assertEquals(settled * 10L, v.totalSpent().minor());
            assertTrue(settled <= n);
        } finally {
            pool.shutdownNow();
        }
    }
}
