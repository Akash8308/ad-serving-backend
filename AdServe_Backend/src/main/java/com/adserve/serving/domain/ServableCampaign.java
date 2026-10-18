package com.adserve.serving.domain;

import com.adserve.shared.AdFormat;
import com.adserve.shared.CampaignId;
import com.adserve.shared.CampaignSchedule;
import com.adserve.shared.Money;
import com.adserve.shared.TargetingRules;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Immutable serving read model, a projection of an ACTIVE campaign. It deliberately does not evaluate status or funds:
 * those are decided by the budget ledger at reservation time, so a stale snapshot can never cause an ineligible serve.
 */
public record ServableCampaign(CampaignId id, Money bid, CampaignSchedule schedule, TargetingRules targeting,
                               List<ServableAd> ads) {
    public ServableCampaign {
        ads = List.copyOf(ads);
    }

    public boolean isEligibleFor(AdRequest request, Instant now) {
        return schedule.isActiveAt(now)
                && targeting.matchesRegion(request.region())
                && targeting.matchesAudience(request.audienceSegments())
                && selectAd(request.format()).isPresent()
                && matchedTerms(request) >= 1;
    }

    public int matchedTerms(AdRequest request) {
        return targeting.matchedTerms(request.keywords(), request.categories());
    }

    /** Deterministic: the matching ad with the lowest id. */
    public Optional<ServableAd> selectAd(AdFormat format) {
        return ads.stream()
                .filter(ad -> ad.format() == format)
                .min(Comparator.comparing(ServableAd::id));
    }
}
