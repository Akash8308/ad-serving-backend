package com.adserve.budget.domain;

import com.adserve.shared.CampaignId;
import com.adserve.shared.Money;
import com.adserve.shared.ReservationId;

import java.time.Instant;
import java.time.LocalDate;

/** One serve. Doubles as the durable record that an ad was served (attribution, rule 5). */
public record Reservation(ReservationId id, CampaignId campaignId, String publisherId, String requestId,
                          Money amount, LocalDate day, ReservationStatus status,
                          Instant createdAt, Instant expiresAt, Instant settledAt, AdSnapshot ad) {

    public Reservation withStatus(ReservationStatus newStatus, Instant settled) {
        return new Reservation(id, campaignId, publisherId, requestId, amount, day, newStatus,
                createdAt, expiresAt, settled, ad);
    }
}
