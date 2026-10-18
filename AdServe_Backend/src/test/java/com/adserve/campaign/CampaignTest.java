package com.adserve.campaign;

import com.adserve.budget.domain.BudgetView;
import com.adserve.campaign.domain.Campaign;
import com.adserve.campaign.domain.CampaignEvent;
import com.adserve.campaign.domain.CampaignNotFound;
import com.adserve.campaign.domain.CampaignNotResumable;
import com.adserve.campaign.domain.CampaignStatus;
import com.adserve.campaign.domain.InvalidStateTransition;
import com.adserve.campaign.domain.ServingState;
import com.adserve.shared.CampaignSchedule;
import com.adserve.shared.Money;
import com.adserve.shared.TargetingRules;
import com.adserve.shared.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.adserve.testsupport.TestData.ADVERTISER;
import static com.adserve.testsupport.TestData.NOW;
import static com.adserve.testsupport.TestData.OTHER_ADVERTISER;
import static com.adserve.testsupport.TestData.activeCampaign;
import static com.adserve.testsupport.TestData.ad;
import static com.adserve.testsupport.TestData.draftCampaign;
import static com.adserve.testsupport.TestData.runningSchedule;
import static com.adserve.testsupport.TestData.targeting;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CampaignTest {

    private static BudgetView budget(long limit, long spent, long reserved, long dailyLimit, long dailyCommitted) {
        return new BudgetView(Money.inr(limit), Money.inr(spent), Money.inr(reserved),
                Money.inr(dailyLimit), Money.inr(dailyCommitted), Money.inr(0));
    }

    // CMP-01
    @Test
    void newCampaignIsDraftAndCanBeActivated() {
        Campaign c = draftCampaign(1200, "running");
        assertEquals(CampaignStatus.DRAFT, c.status());
        c.activate(NOW);
        assertEquals(CampaignStatus.ACTIVE, c.status());
    }

    // CMP-02
    @Test
    void creationRejectsInvalidConfiguration() {
        var sched = runningSchedule();
        var t = targeting("running");
        var ads = List.of(ad(com.adserve.shared.AdFormat.BANNER));
        assertThrows(ValidationException.class,
                () -> com.adserve.campaign.domain.Campaign.create(ADVERTISER, " ", Money.inr(100), sched, t, ads));
        assertThrows(ValidationException.class,
                () -> com.adserve.campaign.domain.Campaign.create(ADVERTISER, "n", Money.inr(0), sched, t, ads));
        assertThrows(ValidationException.class,
                () -> com.adserve.campaign.domain.Campaign.create(ADVERTISER, "n", Money.inr(100), sched, t, List.of()));
        assertThrows(ValidationException.class, () -> new CampaignSchedule(NOW, NOW));
        assertThrows(ValidationException.class, () -> new CampaignSchedule(NOW, NOW.minusSeconds(1)));
        assertThrows(ValidationException.class, () -> new TargetingRules(Set.of(), Set.of(), Set.of(), Set.of()));
        assertThrows(UnsupportedOperationException.class, () -> targeting("running").regions().add("IN"));
    }

    // CMP-03
    @Test
    void invalidTransitionsAreRejected() {
        Campaign draft = draftCampaign(100, "running");
        assertThrows(InvalidStateTransition.class, draft::pause);
        assertThrows(InvalidStateTransition.class, () -> draft.resume(NOW, budget(1000, 0, 0, 1000, 0)));

        Campaign active = activeCampaign(100, "running");
        assertThrows(InvalidStateTransition.class, () -> active.activate(NOW));
        assertThrows(InvalidStateTransition.class, () -> active.resume(NOW, budget(1000, 0, 0, 1000, 0)));

        Campaign done = activeCampaign(100, "running");
        done.complete();
        assertThrows(InvalidStateTransition.class, () -> done.activate(NOW));
        assertThrows(InvalidStateTransition.class, done::pause);
        assertThrows(InvalidStateTransition.class, done::complete);
        assertThrows(InvalidStateTransition.class, () -> done.resume(NOW, budget(1000, 0, 0, 1000, 0)));
    }

    @Test
    void cannotActivateAfterEndDate() {
        Campaign c = draftCampaign(100, "running");
        Instant afterEnd = c.schedule().endAt().plusSeconds(1);
        assertThrows(InvalidStateTransition.class, () -> c.activate(afterEnd));
    }

    // CMP-04
    @Test
    void pausedCampaignResumesInsideSchedule() {
        Campaign c = activeCampaign(100, "running");
        c.pause();
        assertEquals(CampaignStatus.PAUSED, c.status());
        c.resume(NOW, budget(1000, 0, 0, 1000, 0));
        assertEquals(CampaignStatus.ACTIVE, c.status());
    }

    // CMP-05
    @Test
    void resumeIsRejectedAfterTheEndDate() {
        Campaign c = activeCampaign(100, "running");
        c.pause();
        Instant atEnd = c.schedule().endAt();
        assertThrows(CampaignNotResumable.class, () -> c.resume(atEnd, budget(1000, 0, 0, 1000, 0)));
        assertEquals(CampaignStatus.PAUSED, c.status());
    }

    // CMP-06 (domain part)
    @Test
    void resumeIsRejectedWhenRemainingBudgetCannotAffordTheBid() {
        Campaign c = activeCampaign(1000, "running");
        c.pause();
        BudgetView nearlyEmpty = budget(1500, 1000, 0, 1500, 0);
        assertThrows(CampaignNotResumable.class, () -> c.resume(NOW, nearlyEmpty));
        BudgetView exactlyEnough = budget(2000, 1000, 0, 2000, 0);
        c.resume(NOW, exactlyEnough);
        assertEquals(CampaignStatus.ACTIVE, c.status());
    }

    // CMP-07
    @Test
    void updateChangesConfigurationButNotWhenCompleted() {
        Campaign c = activeCampaign(100, "running");
        c.update("Renamed", Money.inr(250), runningSchedule(), targeting("fitness"), List.of(ad(com.adserve.shared.AdFormat.BANNER)));
        assertEquals("Renamed", c.name());
        assertEquals(250, c.bid().minor());
        assertTrue(c.targeting().keywords().contains("fitness"));
        c.complete();
        assertThrows(InvalidStateTransition.class,
                () -> c.update("x", Money.inr(1), runningSchedule(), targeting("a"), List.of(ad(com.adserve.shared.AdFormat.BANNER))));
    }

    // CMP-09 (domain part)
    @Test
    void foreignAdvertiserSeesCampaignAsNotFound() {
        Campaign c = activeCampaign(100, "running");
        c.assertOwnedBy(ADVERTISER);
        assertThrows(CampaignNotFound.class, () -> c.assertOwnedBy(OTHER_ADVERTISER));
    }

    @Test
    void behaviorMethodsRecordDomainEventsOnce() {
        Campaign c = draftCampaign(100, "running");
        c.activate(NOW);
        c.pause();
        List<CampaignEvent> events = c.pullEvents();
        assertEquals(2, events.size());
        assertInstanceOf(CampaignEvent.CampaignActivated.class, events.get(0));
        assertInstanceOf(CampaignEvent.CampaignPaused.class, events.get(1));
        assertTrue(c.pullEvents().isEmpty());
    }

    @Test
    void servingStateIsDerivedFromStatusScheduleAndBudget() {
        Campaign c = activeCampaign(1000, "running");
        assertEquals(ServingState.SERVING, c.servingState(NOW, budget(5000, 0, 0, 5000, 0)));
        assertEquals(ServingState.BUDGET_EXHAUSTED, c.servingState(NOW, budget(5000, 4500, 0, 5000, 0)));
        assertEquals(ServingState.DAILY_CAP_REACHED, c.servingState(NOW, budget(5000, 0, 0, 1500, 1000)));
        assertEquals(ServingState.EXPIRED, c.servingState(c.schedule().endAt(), budget(5000, 0, 0, 5000, 0)));
        assertEquals(ServingState.NOT_STARTED,
                c.servingState(c.schedule().startAt().minusSeconds(1), budget(5000, 0, 0, 5000, 0)));
        c.pause();
        assertEquals(ServingState.PAUSED, c.servingState(NOW, budget(5000, 0, 0, 5000, 0)));
        assertFalse(c.servingState(NOW, budget(5000, 0, 0, 5000, 0)) == ServingState.SERVING);
    }
}
