package com.adserve.budget.domain;

import com.adserve.shared.CampaignId;
import com.adserve.shared.Money;
import com.adserve.shared.ReservationId;

import java.time.Duration;
import java.time.Instant;

public record ReservationRequest(ReservationId id, CampaignId campaignId, String publisherId, String requestId,
                                 Money amount, AdSnapshot ad, Instant now, Duration ttl) { }
