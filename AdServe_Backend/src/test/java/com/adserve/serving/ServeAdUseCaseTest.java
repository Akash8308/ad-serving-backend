package com.adserve.serving;

import com.adserve.budget.domain.BudgetView;
import com.adserve.serving.application.ServeAdUseCase;
import com.adserve.serving.domain.BidTimesRelevancePolicy;
import com.adserve.serving.domain.NoFillReason;
import com.adserve.serving.domain.ServableCampaign;
import com.adserve.serving.domain.ServeOutcome;
import com.adserve.shared.CampaignId;
import com.adserve.testsupport.FlakyLedger;
import com.adserve.testsupport.InMemoryBudgetLedger;
import com.adserve.testsupport.InMemoryCandidateSource;
import com.adserve.testsupport.MutableClock;
import com.adserve.tracking.domain.TrackingToken;
import com.adserve.tracking.infrastructure.HmacTrackingTokenCodec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.adserve.testsupport.TestData.NOW;
import static com.adserve.testsupport.TestData.PUBLISHER;
import static com.adserve.testsupport.TestData.limits;
import static com.adserve.testsupport.TestData.request;
import static com.adserve.testsupport.TestData.servable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** Behavior tests through the use case with in-memory ports; no mocks, no call-order assertions. */
class ServeAdUseCaseTest {
    static final Duration TTL = Duration.ofMinutes(10);

    MutableClock clock;
    Set<CampaignId> notServable;
    InMemoryBudgetLedger ledger;
    FlakyLedger flakyLedger;
    InMemoryCandidateSource source;
    HmacTrackingTokenCodec codec;
    ServeAdUseCase useCase;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        notServable = ConcurrentHashMap.newKeySet();
        ledger = new InMemoryBudgetLedger((id, now) -> !notServable.contains(id));
        flakyLedger = new FlakyLedger(ledger);
        source = new InMemoryCandidateSource();
        codec = new HmacTrackingTokenCodec("k1", Map.of("k1", new byte[32]));
        useCase = newUseCase(3);
    }

    ServeAdUseCase newUseCase(int maxAttempts) {
        return new ServeAdUseCase(source, new BidTimesRelevancePolicy(), flakyLedger, codec, clock, TTL, maxAttempts);
    }

    /** Registers a candidate in the cache stand-in and opens its budget account. */
    ServableCampaign candidate(long bid, long totalBudget, String... keywords) {
        ServableCampaign c = servable(bid, keywords);
        ledger.open(c.id(), limits(totalBudget, totalBudget));
        source.add(c);
        return c;
    }

    BudgetView budget(ServableCampaign c) {
        return ledger.snapshot(c.id(), clock.instant());
    }

    // SRV-01
    @Test
    void noCandidatesGivesNoFillWithReason() {
        ServeOutcome out = useCase.serve(request("r1", "running"));
        assertEquals(new ServeOutcome.NoFill("r1", NoFillReason.NO_ELIGIBLE_CAMPAIGN), out);
    }

    @Test
    void candidatesThatDoNotMatchTheContextGiveNoFill() {
        candidate(500, 10_000, "yoga");
        ServeOutcome out = useCase.serve(request("r1", "running"));
        assertEquals(new ServeOutcome.NoFill("r1", NoFillReason.NO_ELIGIBLE_CAMPAIGN), out);
    }

    // SRV-02
    @Test
    void singleCandidateIsServedWithAVerifiableTrackingToken() {
        ServableCampaign c = candidate(500, 10_000, "running");
        ServeOutcome.Filled filled = assertInstanceOf(ServeOutcome.Filled.class, useCase.serve(request("r1", "running")));
        assertEquals(c.id(), filled.campaignId());
        assertEquals(c.ads().get(0).id(), filled.ad().adId());
        assertEquals(NOW.plus(TTL), filled.expiresAt());

        TrackingToken token = codec.verify(filled.trackingToken());
        assertEquals(filled.reservationId(), token.reservationId());
        assertEquals(c.id(), token.campaignId());
        assertEquals(filled.ad().adId(), token.adId());
        assertEquals(PUBLISHER, token.publisherId());
        assertEquals(NOW.plus(TTL), token.expiresAt());
        assertEquals(500, budget(c).totalReserved().minor());
    }

    // SRV-03
    @Test
    void theBestRankedCandidateIsServed() {
        candidate(100, 10_000, "running");
        ServableCampaign best = candidate(900, 10_000, "running");
        candidate(300, 10_000, "running");
        ServeOutcome.Filled filled = assertInstanceOf(ServeOutcome.Filled.class, useCase.serve(request("r1", "running")));
        assertEquals(best.id(), filled.campaignId());
    }

    // SRV-04
    @Test
    void topCandidateWithoutFundsIsSkippedForTheNextOne() {
        ServableCampaign broke = candidate(900, 800, "running");
        ServableCampaign funded = candidate(300, 10_000, "running");
        ServeOutcome.Filled filled = assertInstanceOf(ServeOutcome.Filled.class, useCase.serve(request("r1", "running")));
        assertEquals(funded.id(), filled.campaignId());
        assertEquals(0, budget(broke).totalReserved().minor());
    }

    // SRV-05 (R-02 regression): the cache still lists a campaign that has since been paused
    @Test
    void aCampaignPausedAfterTheCacheWasFilledIsNeverServed() {
        ServableCampaign stale = candidate(900, 10_000, "running");
        notServable.add(stale.id());
        ServeOutcome out = useCase.serve(request("r1", "running"));
        assertEquals(new ServeOutcome.NoFill("r1", NoFillReason.BUDGET_UNAVAILABLE), out);
        assertEquals(0, budget(stale).totalReserved().minor());
    }

    @Test
    void aStalePausedCandidateFallsBackToTheNextRankedCandidate() {
        ServableCampaign stale = candidate(900, 10_000, "running");
        ServableCampaign live = candidate(100, 10_000, "running");
        notServable.add(stale.id());
        ServeOutcome.Filled filled = assertInstanceOf(ServeOutcome.Filled.class, useCase.serve(request("r1", "running")));
        assertEquals(live.id(), filled.campaignId());
    }

    // SRV-06: a snapshot whose schedule has since ended is filtered by eligibility
    @Test
    void aCandidateWhoseScheduleHasEndedIsNotServed() {
        candidate(900, 10_000, "running");
        clock.set(NOW.plus(Duration.ofDays(31)));
        ServeOutcome out = useCase.serve(request("r1", "running"));
        assertEquals(new ServeOutcome.NoFill("r1", NoFillReason.NO_ELIGIBLE_CAMPAIGN), out);
    }

    // SRV-07
    @Test
    void whenEveryAttemptIsRejectedTheResultIsBudgetUnavailable() {
        candidate(900, 100, "running");
        candidate(800, 100, "running");
        ServeOutcome out = useCase.serve(request("r1", "running"));
        assertEquals(new ServeOutcome.NoFill("r1", NoFillReason.BUDGET_UNAVAILABLE), out);
    }

    @Test
    void reservationAttemptsAreBoundedToKeepLatencyPredictable() {
        for (int i = 0; i < 3; i++) {
            candidate(900 - i, 100, "running");          // three exhausted candidates ranked first
        }
        ServableCampaign fourth = candidate(100, 10_000, "running");
        assertEquals(new ServeOutcome.NoFill("r1", NoFillReason.BUDGET_UNAVAILABLE), useCase.serve(request("r1", "running")));
        ServeOutcome.Filled filled = assertInstanceOf(ServeOutcome.Filled.class,
                newUseCase(5).serve(request("r2", "running")));
        assertEquals(fourth.id(), filled.campaignId());
    }

    // SRV-09
    @Test
    void whenNoCandidateSourceIsAvailableTheServeIsAControlledNoFill() {
        source.unavailable = true;
        assertEquals(new ServeOutcome.NoFill("r1", NoFillReason.TEMPORARILY_UNAVAILABLE),
                useCase.serve(request("r1", "running")));
    }

    @Test
    void whenTheLedgerIsDownTheServeIsAControlledNoFillAndNothingIsReserved() {
        ServableCampaign c = candidate(500, 10_000, "running");
        flakyLedger.down = true;
        assertEquals(new ServeOutcome.NoFill("r1", NoFillReason.TEMPORARILY_UNAVAILABLE),
                useCase.serve(request("r1", "running")));
        flakyLedger.down = false;
        assertEquals(0, budget(c).totalReserved().minor());
        assertInstanceOf(ServeOutcome.Filled.class, useCase.serve(request("r2", "running")));
    }

    // SRV-11
    @Test
    void retryingTheSameRequestReturnsTheSameAdAndReservesOnce() {
        ServableCampaign c = candidate(500, 10_000, "running");
        ServeOutcome.Filled first = assertInstanceOf(ServeOutcome.Filled.class, useCase.serve(request("same", "running")));
        clock.advance(Duration.ofSeconds(3));
        ServeOutcome.Filled retry = assertInstanceOf(ServeOutcome.Filled.class, useCase.serve(request("same", "running")));
        assertEquals(first.reservationId(), retry.reservationId());
        assertEquals(first.ad(), retry.ad());
        assertEquals(500, budget(c).totalReserved().minor());
        assertEquals(1, ledger.reservationCount());
    }

    @Test
    void differentRequestsReserveSeparately() {
        ServableCampaign c = candidate(500, 10_000, "running");
        useCase.serve(request("a", "running"));
        useCase.serve(request("b", "running"));
        assertEquals(1000, budget(c).totalReserved().minor());
    }

    // R-01 / BUD-11 at use-case level: many concurrent serves can never overspend one campaign
    @Test
    void concurrentServesNeverReserveMoreThanTheBudget() throws Exception {
        ServableCampaign c = candidate(100, 2_000, "running");   // funds for exactly 20 serves
        var pool = java.util.concurrent.Executors.newFixedThreadPool(16);
        try {
            var jobs = new java.util.ArrayList<java.util.concurrent.Callable<ServeOutcome>>();
            for (int i = 0; i < 100; i++) {
                String id = "c-" + i;
                jobs.add(() -> useCase.serve(request(id, "running")));
            }
            long filled = 0;
            for (var f : pool.invokeAll(jobs)) {
                if (f.get() instanceof ServeOutcome.Filled) {
                    filled++;
                }
            }
            assertEquals(20, filled);
            assertEquals(2_000, budget(c).totalReserved().minor());
        } finally {
            pool.shutdownNow();
        }
    }
}
