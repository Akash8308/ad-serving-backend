package com.adserve.serving.application;

import com.adserve.budget.domain.AdSnapshot;
import com.adserve.budget.domain.BudgetLedger;
import com.adserve.budget.domain.LedgerUnavailable;
import com.adserve.budget.domain.Reservation;
import com.adserve.budget.domain.ReservationRequest;
import com.adserve.budget.domain.ReservationResult;
import com.adserve.serving.domain.AdRequest;
import com.adserve.serving.domain.AdSelectionPolicy;
import com.adserve.serving.domain.CandidateSource;
import com.adserve.serving.domain.CandidateSourceUnavailable;
import com.adserve.serving.domain.NoFillReason;
import com.adserve.serving.domain.RankedCandidate;
import com.adserve.serving.domain.ServableCampaign;
import com.adserve.serving.domain.ServeOutcome;
import com.adserve.shared.ReservationId;
import com.adserve.tracking.domain.TrackingToken;
import com.adserve.tracking.domain.TrackingTokenCodec;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * UC-08. Orchestration only: candidate hints -> rank -> authoritative reservation (the gate).
 * The serving path never touches Kafka or analytics, so an analytics failure cannot fail a serve (rule 9).
 */
public final class ServeAdUseCase {
    private final CandidateSource candidateSource;
    private final AdSelectionPolicy policy;
    private final BudgetLedger ledger;
    private final TrackingTokenCodec tokenCodec;
    private final Clock clock;
    private final Duration reservationTtl;
    private final int maxReservationAttempts;

    public ServeAdUseCase(CandidateSource candidateSource, AdSelectionPolicy policy, BudgetLedger ledger,
                          TrackingTokenCodec tokenCodec, Clock clock, Duration reservationTtl,
                          int maxReservationAttempts) {
        if (maxReservationAttempts < 1) {
            throw new IllegalArgumentException("maxReservationAttempts must be >= 1");
        }
        this.candidateSource = candidateSource;
        this.policy = policy;
        this.ledger = ledger;
        this.tokenCodec = tokenCodec;
        this.clock = clock;
        this.reservationTtl = reservationTtl;
        this.maxReservationAttempts = maxReservationAttempts;
    }

    public ServeOutcome serve(AdRequest request) {
        Instant now = clock.instant();

        List<ServableCampaign> candidates;
        try {
            candidates = candidateSource.candidates(request.format(), request.region());
        } catch (CandidateSourceUnavailable e) {
            return noFill(request, NoFillReason.TEMPORARILY_UNAVAILABLE);
        }

        List<RankedCandidate> ranked = policy.rank(request, candidates, now);
        if (ranked.isEmpty()) {
            return noFill(request, NoFillReason.NO_ELIGIBLE_CAMPAIGN);
        }

        int attempts = 0;
        for (RankedCandidate candidate : ranked) {
            if (attempts++ >= maxReservationAttempts) {
                break;
            }
            ReservationResult result;
            try {
                result = ledger.reserve(toReservationRequest(request, candidate, now));
            } catch (LedgerUnavailable e) {
                return noFill(request, NoFillReason.TEMPORARILY_UNAVAILABLE);
            }
            switch (result) {
                case ReservationResult.Reserved reserved -> {
                    return filled(request, reserved.reservation());
                }
                case ReservationResult.Duplicate duplicate -> {
                    return filled(request, duplicate.existing());
                }
                case ReservationResult.Rejected rejected -> {
                    // stale hint or insufficient funds: try the next candidate
                }
            }
        }
        return noFill(request, NoFillReason.BUDGET_UNAVAILABLE);
    }

    private ReservationRequest toReservationRequest(AdRequest request, RankedCandidate candidate, Instant now) {
        AdSnapshot snapshot = new AdSnapshot(candidate.ad().id(), candidate.ad().headline(),
                candidate.ad().creativeUrl(), candidate.ad().destinationUrl());
        return new ReservationRequest(ReservationId.newId(), candidate.campaign().id(), request.publisherId(),
                request.requestId(), candidate.campaign().bid(), snapshot, now, reservationTtl);
    }

    private ServeOutcome filled(AdRequest request, Reservation reservation) {
        TrackingToken token = new TrackingToken(reservation.id(), reservation.campaignId(),
                reservation.ad().adId(), reservation.publisherId(), reservation.expiresAt());
        return new ServeOutcome.Filled(request.requestId(), reservation.campaignId(), reservation.ad(),
                reservation.id(), tokenCodec.sign(token), reservation.expiresAt());
    }

    private ServeOutcome noFill(AdRequest request, NoFillReason reason) {
        return new ServeOutcome.NoFill(request.requestId(), reason);
    }
}
