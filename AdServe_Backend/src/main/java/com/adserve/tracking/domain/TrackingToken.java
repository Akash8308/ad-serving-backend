package com.adserve.tracking.domain;

import com.adserve.shared.AdId;
import com.adserve.shared.CampaignId;
import com.adserve.shared.ReservationId;

import java.time.Instant;

/** Signed proof that a specific ad was served. Clients never supply campaign, ad, or event ids themselves. */
public record TrackingToken(ReservationId reservationId, CampaignId campaignId, AdId adId, String publisherId,
                            Instant expiresAt) {

    /** Deterministic event ids: one impression and one click per serve, so duplicates collapse naturally. */
    public String impressionEventId() {
        return reservationId + ":IMP";
    }

    public String clickEventId() {
        return reservationId + ":CLK";
    }
}
