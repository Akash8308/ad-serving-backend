package com.adserve.testsupport;

import com.adserve.budget.domain.BudgetAccount;
import com.adserve.budget.domain.BudgetAccountNotFound;
import com.adserve.budget.domain.BudgetLedger;
import com.adserve.budget.domain.BudgetLimits;
import com.adserve.budget.domain.BudgetView;
import com.adserve.budget.domain.LimitChangeResult;
import com.adserve.budget.domain.Reservation;
import com.adserve.budget.domain.ReservationRequest;
import com.adserve.budget.domain.ReservationResult;
import com.adserve.budget.domain.ReservationStatus;
import com.adserve.budget.domain.SettlementResult;
import com.adserve.shared.CampaignId;
import com.adserve.shared.ReservationId;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Test double that follows the same step order as the PostgreSQL ledger: eligibility gate, funds check, duplicate check,
 * then write. All methods are synchronized, standing in for the row lock plus conditional UPDATE.
 */
public final class InMemoryBudgetLedger implements BudgetLedger {

    /** Stand-in for the "campaigns" join in the reservation SQL: is the campaign ACTIVE and inside its schedule? */
    @FunctionalInterface
    public interface CampaignGate {
        boolean isServable(CampaignId campaignId, Instant now);
    }

    private final CampaignGate gate;
    private final Map<CampaignId, BudgetAccount> accounts = new HashMap<>();
    private final Map<ReservationId, Reservation> reservations = new HashMap<>();
    private final Map<String, ReservationId> byRequest = new HashMap<>();

    public InMemoryBudgetLedger(CampaignGate gate) {
        this.gate = gate;
    }

    @Override
    public synchronized void open(CampaignId campaignId, BudgetLimits limits) {
        if (accounts.putIfAbsent(campaignId, new BudgetAccount(limits)) != null) {
            throw new IllegalStateException("budget account already exists: " + campaignId);
        }
    }

    @Override
    public synchronized ReservationResult reserve(ReservationRequest r) {
        BudgetAccount account = accounts.get(r.campaignId());
        if (account == null || !gate.isServable(r.campaignId(), r.now())) {
            return new ReservationResult.Rejected();
        }
        LocalDate today = dayOf(r.now());
        if (!account.canReserve(r.amount(), today)) {
            return new ReservationResult.Rejected();
        }
        String key = r.publisherId() + '\u0000' + r.requestId();
        ReservationId existingId = byRequest.get(key);
        if (existingId != null) {
            return new ReservationResult.Duplicate(reservations.get(existingId));
        }
        account.tryReserve(r.amount(), today);
        Reservation reservation = new Reservation(r.id(), r.campaignId(), r.publisherId(), r.requestId(), r.amount(),
                today, ReservationStatus.RESERVED, r.now(), r.now().plus(r.ttl()), null, r.ad());
        reservations.put(reservation.id(), reservation);
        byRequest.put(key, reservation.id());
        return new ReservationResult.Reserved(reservation);
    }

    @Override
    public synchronized SettlementResult settle(ReservationId id, Instant now) {
        Reservation res = reservations.get(id);
        if (res == null) {
            return new SettlementResult.NotBillable(SettlementResult.Reason.UNKNOWN);
        }
        if (res.status() == ReservationStatus.SETTLED) {
            return new SettlementResult.AlreadySettled(res);
        }
        if (res.status() == ReservationStatus.EXPIRED || !res.expiresAt().isAfter(now)) {
            return new SettlementResult.NotBillable(SettlementResult.Reason.WINDOW_EXPIRED);
        }
        accounts.get(res.campaignId()).settle(res.amount(), res.day());
        Reservation settled = res.withStatus(ReservationStatus.SETTLED, now);
        reservations.put(id, settled);
        return new SettlementResult.Settled(settled);
    }

    @Override
    public synchronized int expireDue(Instant now, int batchSize) {
        List<Reservation> due = new ArrayList<>();
        for (Reservation r : reservations.values()) {
            if (r.status() == ReservationStatus.RESERVED && !r.expiresAt().isAfter(now)) {
                due.add(r);
            }
        }
        due.sort(Comparator.comparing(Reservation::expiresAt));
        int released = 0;
        for (Reservation r : due) {
            if (released >= batchSize) {
                break;
            }
            accounts.get(r.campaignId()).release(r.amount(), r.day());
            reservations.put(r.id(), r.withStatus(ReservationStatus.EXPIRED, null));
            released++;
        }
        return released;
    }

    @Override
    public synchronized LimitChangeResult adjustLimits(CampaignId campaignId, BudgetLimits limits, Instant now) {
        BudgetAccount account = account(campaignId);
        return account.adjustLimits(limits, dayOf(now)) ? LimitChangeResult.CHANGED : LimitChangeResult.BELOW_COMMITTED;
    }

    @Override
    public synchronized BudgetView snapshot(CampaignId campaignId, Instant now) {
        return account(campaignId).view(dayOf(now));
    }

    /** Test inspection helper. */
    public synchronized Reservation reservation(ReservationId id) {
        return reservations.get(id);
    }

    public synchronized int reservationCount() {
        return reservations.size();
    }

    private BudgetAccount account(CampaignId id) {
        BudgetAccount account = accounts.get(id);
        if (account == null) {
            throw new BudgetAccountNotFound("no budget account for campaign " + id);
        }
        return account;
    }

    private static LocalDate dayOf(Instant instant) {
        return LocalDate.ofInstant(instant, ZoneOffset.UTC);
    }
}
