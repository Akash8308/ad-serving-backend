package com.adserve.campaign;

import com.adserve.budget.domain.AdSnapshot;
import com.adserve.budget.domain.BudgetBelowCommitted;
import com.adserve.budget.domain.ReservationRequest;
import com.adserve.budget.domain.ReservationResult;
import com.adserve.campaign.application.CampaignView;
import com.adserve.campaign.application.CreateCampaignCommand;
import com.adserve.campaign.application.CreateCampaignUseCase;
import com.adserve.campaign.application.ManageCampaignUseCase;
import com.adserve.campaign.application.UpdateCampaignCommand;
import com.adserve.campaign.domain.Campaign;
import com.adserve.campaign.domain.CampaignEvent;
import com.adserve.campaign.domain.CampaignNotFound;
import com.adserve.campaign.domain.CampaignNotResumable;
import com.adserve.campaign.domain.CampaignStatus;
import com.adserve.campaign.domain.ConcurrentModification;
import com.adserve.campaign.domain.ServingState;
import com.adserve.shared.AdFormat;
import com.adserve.shared.AdId;
import com.adserve.shared.CampaignId;
import com.adserve.shared.Money;
import com.adserve.shared.ReservationId;
import com.adserve.testsupport.InMemoryBudgetLedger;
import com.adserve.testsupport.InMemoryCampaignRepository;
import com.adserve.testsupport.MutableClock;
import com.adserve.testsupport.RecordingEventPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static com.adserve.testsupport.TestData.ADVERTISER;
import static com.adserve.testsupport.TestData.NOW;
import static com.adserve.testsupport.TestData.OTHER_ADVERTISER;
import static com.adserve.testsupport.TestData.PUBLISHER;
import static com.adserve.testsupport.TestData.ad;
import static com.adserve.testsupport.TestData.limits;
import static com.adserve.testsupport.TestData.runningSchedule;
import static com.adserve.testsupport.TestData.targeting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CampaignUseCasesTest {
    InMemoryCampaignRepository repo;
    InMemoryBudgetLedger ledger;
    RecordingEventPublisher events;
    MutableClock clock;
    CreateCampaignUseCase create;
    ManageCampaignUseCase manage;

    @BeforeEach
    void setUp() {
        repo = new InMemoryCampaignRepository();
        // Mirrors the SQL gate: a campaign is servable only while ACTIVE and inside its schedule.
        ledger = new InMemoryBudgetLedger((id, now) -> repo.findById(id)
                .map(c -> c.status() == CampaignStatus.ACTIVE && c.schedule().isActiveAt(now)).orElse(false));
        events = new RecordingEventPublisher();
        clock = new MutableClock(NOW);
        create = new CreateCampaignUseCase(repo, ledger);
        manage = new ManageCampaignUseCase(repo, ledger, events, clock);
    }

    Campaign createCampaign(long bid, long total, long daily) {
        return create.create(new CreateCampaignCommand(ADVERTISER, "Monsoon Running Shoes", Money.inr(bid),
                limits(total, daily), runningSchedule(), targeting("running"), List.of(ad(AdFormat.BANNER))));
    }

    Campaign activeCampaign(long bid, long total, long daily) {
        Campaign c = createCampaign(bid, total, daily);
        manage.activate(ADVERTISER, c.id());
        events.published.clear();
        return c;
    }

    ReservationResult reserve(CampaignId id, String requestId, long amount) {
        AdSnapshot ad = new AdSnapshot(AdId.newId(), "h", "https://c.example.com/a.png", "https://e.example.com");
        return ledger.reserve(new ReservationRequest(ReservationId.newId(), id, PUBLISHER, requestId,
                Money.inr(amount), ad, clock.instant(), Duration.ofMinutes(10)));
    }

    @Test
    void creatingACampaignOpensItsBudgetAccount() {
        Campaign c = createCampaign(1000, 50_000, 5_000);
        var view = manage.get(ADVERTISER, c.id());
        assertEquals(CampaignStatus.DRAFT, view.campaign().status());
        assertEquals(50_000, view.budget().totalLimit().minor());
        assertEquals(5_000, view.budget().dailyLimit().minor());
        assertEquals(ServingState.DRAFT, view.servingState());
    }

    @Test
    void lifecycleChangesArePersistedAndPublishedInOrder() {
        Campaign c = createCampaign(1000, 50_000, 5_000);
        manage.activate(ADVERTISER, c.id());
        manage.pause(ADVERTISER, c.id());
        manage.resume(ADVERTISER, c.id());
        manage.complete(ADVERTISER, c.id());
        assertEquals(CampaignStatus.COMPLETED, repo.findById(c.id()).orElseThrow().status());
        assertEquals(List.of(CampaignEvent.CampaignActivated.class, CampaignEvent.CampaignPaused.class,
                        CampaignEvent.CampaignResumed.class, CampaignEvent.CampaignCompleted.class),
                events.published.stream().map(Object::getClass).map(k -> (Class<?>) k).toList());
    }

    // CMP-09
    @Test
    void anotherAdvertisersCampaignLooksNonExistent() {
        Campaign c = activeCampaign(1000, 50_000, 5_000);
        assertThrows(CampaignNotFound.class, () -> manage.get(OTHER_ADVERTISER, c.id()));
        assertThrows(CampaignNotFound.class, () -> manage.pause(OTHER_ADVERTISER, c.id()));
        assertThrows(CampaignNotFound.class, () -> manage.complete(OTHER_ADVERTISER, c.id()));
        assertEquals(CampaignStatus.ACTIVE, repo.findById(c.id()).orElseThrow().status());
        assertThrows(CampaignNotFound.class, () -> manage.get(ADVERTISER, CampaignId.newId()));
    }

    // BR-3 end to end (R-02): once paused, the ledger refuses reservations immediately
    @Test
    void aPausedCampaignCannotReceiveReservations() {
        Campaign c = activeCampaign(1000, 50_000, 5_000);
        assertInstanceOf(ReservationResult.Reserved.class, reserve(c.id(), "r1", 1000));
        manage.pause(ADVERTISER, c.id());
        assertInstanceOf(ReservationResult.Rejected.class, reserve(c.id(), "r2", 1000));
        manage.resume(ADVERTISER, c.id());
        assertInstanceOf(ReservationResult.Reserved.class, reserve(c.id(), "r3", 1000));
    }

    // CMP-06
    @Test
    void resumeIsRejectedWhenTheRemainingBudgetCannotAffordAnotherServe() {
        Campaign c = activeCampaign(1000, 1500, 1500);
        ReservationResult.Reserved r = assertInstanceOf(ReservationResult.Reserved.class, reserve(c.id(), "r1", 1000));
        ledger.settle(r.reservation().id(), clock.instant());     // 1000 spent, 500 left < bid
        manage.pause(ADVERTISER, c.id());
        assertThrows(CampaignNotResumable.class, () -> manage.resume(ADVERTISER, c.id()));
        assertEquals(CampaignStatus.PAUSED, repo.findById(c.id()).orElseThrow().status());
    }

    @Test
    void resumeIsRejectedAfterTheScheduleEnded() {
        Campaign c = activeCampaign(1000, 50_000, 5_000);
        manage.pause(ADVERTISER, c.id());
        clock.set(c.schedule().endAt());
        assertThrows(CampaignNotResumable.class, () -> manage.resume(ADVERTISER, c.id()));
    }

    // CMP-08
    @Test
    void budgetCannotBeLoweredBelowSpentAndReservedFunds() {
        Campaign c = activeCampaign(1000, 50_000, 5_000);
        reserve(c.id(), "r1", 1000);
        UpdateCampaignCommand lowering = new UpdateCampaignCommand(ADVERTISER, c.id(), "Changed name", Money.inr(1000),
                limits(500, 500), runningSchedule(), targeting("running"), List.of(ad(AdFormat.BANNER)));
        assertThrows(BudgetBelowCommitted.class, () -> manage.update(lowering));
        assertEquals("Monsoon Running Shoes", repo.findById(c.id()).orElseThrow().name());
        assertEquals(50_000, manage.get(ADVERTISER, c.id()).budget().totalLimit().minor());
    }

    @Test
    void updateCanRaiseTheBudgetAndChangeConfiguration() {
        Campaign c = activeCampaign(1000, 50_000, 5_000);
        manage.update(new UpdateCampaignCommand(ADVERTISER, c.id(), "Renamed", Money.inr(1500), limits(90_000, 9_000),
                runningSchedule(), targeting("fitness"), List.of(ad(AdFormat.BANNER))));
        CampaignView view = manage.get(ADVERTISER, c.id());
        assertEquals("Renamed", view.campaign().name());
        assertEquals(1500, view.campaign().bid().minor());
        assertEquals(90_000, view.budget().totalLimit().minor());
        assertInstanceOf(CampaignEvent.CampaignUpdated.class, events.published.get(events.published.size() - 1));
    }

    // CMP-10
    @Test
    void twoConcurrentEditsOfTheSameCampaignDoNotBothSucceed() {
        Campaign created = activeCampaign(1000, 50_000, 5_000);
        Campaign copyA = repo.findById(created.id()).orElseThrow();
        Campaign copyB = repo.findById(created.id()).orElseThrow();
        copyA.pause();
        repo.save(copyA);
        copyB.complete();
        assertThrows(ConcurrentModification.class, () -> repo.save(copyB));
        assertEquals(CampaignStatus.PAUSED, repo.findById(created.id()).orElseThrow().status());
    }

    @Test
    void getShowsTheDerivedServingState() {
        Campaign c = activeCampaign(1000, 1500, 1500);
        assertEquals(ServingState.SERVING, manage.get(ADVERTISER, c.id()).servingState());
        ReservationResult.Reserved r = assertInstanceOf(ReservationResult.Reserved.class, reserve(c.id(), "r1", 1000));
        ledger.settle(r.reservation().id(), clock.instant());
        assertEquals(ServingState.BUDGET_EXHAUSTED, manage.get(ADVERTISER, c.id()).servingState());
    }
}
