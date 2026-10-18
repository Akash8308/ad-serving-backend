package com.adserve.serving.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * relevanceBp = 10_000 * matchedTerms / requestTerms (integer division)
 * score       = bidMinor * relevanceBp                  (long arithmetic, no floating point)
 * order       = score desc, relevanceBp desc, bid desc, campaignId asc, adId asc  (deterministic, rule 8)
 */
public final class BidTimesRelevancePolicy implements AdSelectionPolicy {

    private static final Comparator<RankedCandidate> ORDER = Comparator
            .comparingLong(RankedCandidate::score).reversed()
            .thenComparing(Comparator.comparingInt(RankedCandidate::relevanceBp).reversed())
            .thenComparing(Comparator.comparingLong((RankedCandidate c) -> c.campaign().bid().minor()).reversed())
            .thenComparing(c -> c.campaign().id())
            .thenComparing(c -> c.ad().id());

    @Override
    public List<RankedCandidate> rank(AdRequest request, Collection<ServableCampaign> candidates, Instant now) {
        List<RankedCandidate> ranked = new ArrayList<>();
        for (ServableCampaign campaign : candidates) {
            if (!campaign.isEligibleFor(request, now)) {
                continue;
            }
            ServableAd ad = campaign.selectAd(request.format()).orElseThrow();
            int relevanceBp = (int) (10_000L * campaign.matchedTerms(request) / request.termCount());
            long score = Math.multiplyExact(campaign.bid().minor(), (long) relevanceBp);
            ranked.add(new RankedCandidate(campaign, ad, score, relevanceBp));
        }
        ranked.sort(ORDER);
        return List.copyOf(ranked);
    }
}
