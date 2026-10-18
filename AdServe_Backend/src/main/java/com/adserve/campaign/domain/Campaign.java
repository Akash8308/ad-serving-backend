package com.adserve.campaign.domain;

import com.adserve.budget.domain.BudgetView;
import com.adserve.shared.AdvertiserId;
import com.adserve.shared.CampaignId;
import com.adserve.shared.CampaignSchedule;
import com.adserve.shared.Money;
import com.adserve.shared.TargetingRules;
import com.adserve.shared.ValidationException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Aggregate root for campaign configuration and lifecycle. It owns every state transition and holds NO money
 * counters: budget lives in the ledger so that serving traffic never contends with configuration edits.
 */
public final class Campaign {
    private final CampaignId id;
    private final AdvertiserId advertiserId;
    private final long version;
    private String name;
    private CampaignStatus status;
    private Money bid;
    private CampaignSchedule schedule;
    private TargetingRules targeting;
    private List<Ad> ads;
    private final List<CampaignEvent> pendingEvents = new ArrayList<>();

    private Campaign(CampaignId id, AdvertiserId advertiserId, long version, String name, CampaignStatus status,
                     Money bid, CampaignSchedule schedule, TargetingRules targeting, List<Ad> ads) {
        this.id = Objects.requireNonNull(id, "id");
        this.advertiserId = Objects.requireNonNull(advertiserId, "advertiserId");
        this.version = version;
        this.status = Objects.requireNonNull(status, "status");
        applyConfiguration(name, bid, schedule, targeting, ads);
    }

    public static Campaign create(AdvertiserId advertiserId, String name, Money bid, CampaignSchedule schedule,
                                  TargetingRules targeting, List<Ad> ads) {
        return create(CampaignId.newId(), advertiserId, name, bid, schedule, targeting, ads);
    }

    public static Campaign create(CampaignId id, AdvertiserId advertiserId, String name, Money bid,
                                  CampaignSchedule schedule, TargetingRules targeting, List<Ad> ads) {
        return new Campaign(id, advertiserId, 0L, name, CampaignStatus.DRAFT, bid, schedule, targeting, ads);
    }

    /** Rehydration for persistence adapters. Performs the same validation as creation. */
    public static Campaign restore(CampaignId id, AdvertiserId advertiserId, long version, String name,
                                   CampaignStatus status, Money bid, CampaignSchedule schedule,
                                   TargetingRules targeting, List<Ad> ads) {
        return new Campaign(id, advertiserId, version, name, status, bid, schedule, targeting, ads);
    }

    /** Copy carrying a different version; used by repositories after a successful save. Pending events are not copied. */
    public Campaign withVersion(long newVersion) {
        return new Campaign(id, advertiserId, newVersion, name, status, bid, schedule, targeting, ads);
    }

    // ---- lifecycle ----

    public void activate(Instant now) {
        requireStatus(CampaignStatus.DRAFT, "activate");
        if (schedule.hasEndedAt(now)) {
            throw new InvalidStateTransition("cannot activate a campaign whose end date has passed");
        }
        status = CampaignStatus.ACTIVE;
        pendingEvents.add(new CampaignEvent.CampaignActivated(id));
    }

    public void pause() {
        requireStatus(CampaignStatus.ACTIVE, "pause");
        status = CampaignStatus.PAUSED;
        pendingEvents.add(new CampaignEvent.CampaignPaused(id));
    }

    /** Rule 7: cannot resume when expired or when the remaining budget cannot afford one more serve. */
    public void resume(Instant now, BudgetView budget) {
        requireStatus(CampaignStatus.PAUSED, "resume");
        if (schedule.hasEndedAt(now)) {
            throw new CampaignNotResumable("campaign has expired");
        }
        if (!budget.canAfford(bid)) {
            throw new CampaignNotResumable("remaining budget cannot afford the bid");
        }
        status = CampaignStatus.ACTIVE;
        pendingEvents.add(new CampaignEvent.CampaignResumed(id));
    }

    public void complete() {
        if (status == CampaignStatus.COMPLETED) {
            throw new InvalidStateTransition("campaign is already completed");
        }
        status = CampaignStatus.COMPLETED;
        pendingEvents.add(new CampaignEvent.CampaignCompleted(id));
    }

    public void update(String name, Money bid, CampaignSchedule schedule, TargetingRules targeting, List<Ad> ads) {
        if (status == CampaignStatus.COMPLETED) {
            throw new InvalidStateTransition("a completed campaign cannot be changed");
        }
        applyConfiguration(name, bid, schedule, targeting, ads);
        pendingEvents.add(new CampaignEvent.CampaignUpdated(id));
    }

    // ---- queries ----

    /** Ownership check that does not leak existence: a foreign campaign looks exactly like a missing one. */
    public void assertOwnedBy(AdvertiserId requester) {
        if (!advertiserId.equals(requester)) {
            throw new CampaignNotFound("campaign not found: " + id);
        }
    }

    public ServingState servingState(Instant now, BudgetView budget) {
        return switch (status) {
            case DRAFT -> ServingState.DRAFT;
            case PAUSED -> ServingState.PAUSED;
            case COMPLETED -> ServingState.COMPLETED;
            case ACTIVE -> {
                if (schedule.hasEndedAt(now)) {
                    yield ServingState.EXPIRED;
                }
                if (!schedule.hasStartedAt(now)) {
                    yield ServingState.NOT_STARTED;
                }
                if (!budget.canAfford(bid)) {
                    yield ServingState.BUDGET_EXHAUSTED;
                }
                if (!budget.canAffordToday(bid)) {
                    yield ServingState.DAILY_CAP_REACHED;
                }
                yield ServingState.SERVING;
            }
        };
    }

    public List<CampaignEvent> pullEvents() {
        List<CampaignEvent> out = List.copyOf(pendingEvents);
        pendingEvents.clear();
        return out;
    }

    public CampaignId id() { return id; }
    public AdvertiserId advertiserId() { return advertiserId; }
    public long version() { return version; }
    public String name() { return name; }
    public CampaignStatus status() { return status; }
    public Money bid() { return bid; }
    public CampaignSchedule schedule() { return schedule; }
    public TargetingRules targeting() { return targeting; }
    public List<Ad> ads() { return ads; }

    // ---- internals ----

    private void requireStatus(CampaignStatus required, String action) {
        if (status != required) {
            throw new InvalidStateTransition("cannot " + action + " a campaign in status " + status);
        }
    }

    private void applyConfiguration(String name, Money bid, CampaignSchedule schedule, TargetingRules targeting,
                                    List<Ad> ads) {
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new ValidationException("campaign name must be 1-200 characters");
        }
        Objects.requireNonNull(bid, "bid");
        if (!bid.isPositive()) {
            throw new ValidationException("bid must be positive");
        }
        Objects.requireNonNull(schedule, "schedule");
        Objects.requireNonNull(targeting, "targeting");
        if (ads == null || ads.isEmpty()) {
            throw new ValidationException("a campaign needs at least one ad");
        }
        Set<Object> seen = new HashSet<>();
        for (Ad ad : ads) {
            if (!seen.add(ad.id())) {
                throw new ValidationException("duplicate ad id: " + ad.id());
            }
        }
        this.name = name.trim();
        this.bid = bid;
        this.schedule = schedule;
        this.targeting = targeting;
        this.ads = List.copyOf(ads);
    }
}
