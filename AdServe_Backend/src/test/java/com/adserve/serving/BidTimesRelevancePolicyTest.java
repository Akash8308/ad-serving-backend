package com.adserve.serving;

import com.adserve.serving.domain.AdRequest;
import com.adserve.serving.domain.BidTimesRelevancePolicy;
import com.adserve.serving.domain.RankedCandidate;
import com.adserve.serving.domain.ServableCampaign;
import com.adserve.shared.CampaignId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static com.adserve.testsupport.TestData.NOW;
import static com.adserve.testsupport.TestData.request;
import static com.adserve.testsupport.TestData.servable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BidTimesRelevancePolicyTest {
    private final BidTimesRelevancePolicy policy = new BidTimesRelevancePolicy();

    private List<RankedCandidate> rank(AdRequest r, List<ServableCampaign> cs) {
        return policy.rank(r, cs, NOW);
    }

    // RNK-01
    @Test
    void higherScoreWins() {
        ServableCampaign low = servable(100, "running");
        ServableCampaign high = servable(500, "running");
        List<RankedCandidate> ranked = rank(request("r", "running"), List.of(low, high));
        assertEquals(high.id(), ranked.get(0).campaign().id());
        assertEquals(low.id(), ranked.get(1).campaign().id());
    }

    // RNK-02
    @Test
    void relevantModerateBidBeatsIrrelevantHighBid() {
        // request has 4 terms. A matches 1/4 (2500bp) with bid 3000 -> 7,500,000. B matches 4/4 (10000bp) with bid 1000 -> 10,000,000.
        ServableCampaign irrelevantHighBid = servable(3000, "running", "x1", "x2", "x3");
        ServableCampaign relevantLowBid = servable(1000, "running", "fitness", "shoes", "marathon");
        AdRequest r = request("r", "running", "fitness", "shoes", "marathon");
        List<RankedCandidate> ranked = rank(r, List.of(irrelevantHighBid, relevantLowBid));
        assertEquals(10_000_000L, ranked.get(0).score());
        assertEquals(relevantLowBid.id(), ranked.get(0).campaign().id());
        assertEquals(7_500_000L, ranked.get(1).score());
    }

    @Test
    void ineligibleCandidatesAreDroppedNotRankedLast() {
        ServableCampaign noMatch = servable(9999, "yoga");
        ServableCampaign match = servable(10, "running");
        List<RankedCandidate> ranked = rank(request("r", "running"), List.of(noMatch, match));
        assertEquals(1, ranked.size());
        assertEquals(match.id(), ranked.get(0).campaign().id());
    }

    @Test
    void scoreTieIsBrokenByHigherRelevanceThenBid() {
        // request: 2 terms. A: bid 2000, matches 1/2 = 5000bp -> 10,000,000. B: bid 1000, matches 2/2 = 10000bp -> 10,000,000.
        ServableCampaign a = servable(2000, "running", "zzz");
        ServableCampaign b = servable(1000, "running", "shoes");
        List<RankedCandidate> ranked = rank(request("r", "running", "shoes"), List.of(a, b));
        assertEquals(ranked.get(0).score(), ranked.get(1).score());
        assertEquals(b.id(), ranked.get(0).campaign().id());
    }

    // RNK-03
    @Test
    void fullTieIsDeterministicByCampaignIdRegardlessOfInputOrder() {
        List<ServableCampaign> campaigns = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            campaigns.add(servable(CampaignId.newId(), 500, "running"));
        }
        AdRequest r = request("r", "running");
        List<CampaignId> expected = rank(r, campaigns).stream().map(c -> c.campaign().id()).toList();
        List<CampaignId> sorted = new ArrayList<>(expected);
        Collections.sort(sorted);
        assertEquals(sorted, expected);
        Random random = new Random(42);
        for (int i = 0; i < 20; i++) {
            List<ServableCampaign> shuffled = new ArrayList<>(campaigns);
            Collections.shuffle(shuffled, random);
            assertEquals(expected, rank(r, shuffled).stream().map(c -> c.campaign().id()).toList());
        }
    }

    @Test
    void emptyInputGivesEmptyRanking() {
        assertTrue(rank(request("r", "running"), List.of()).isEmpty());
    }
}
