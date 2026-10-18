package com.adserve.serving.domain;

import com.adserve.budget.domain.AdSnapshot;
import com.adserve.shared.CampaignId;
import com.adserve.shared.ReservationId;

import java.time.Instant;

public sealed interface ServeOutcome {
    record Filled(String requestId, CampaignId campaignId, AdSnapshot ad, ReservationId reservationId,
                  String trackingToken, Instant expiresAt) implements ServeOutcome { }

    record NoFill(String requestId, NoFillReason reason) implements ServeOutcome { }
}
