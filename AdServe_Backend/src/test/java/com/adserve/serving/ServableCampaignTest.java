package com.adserve.serving;

import com.adserve.serving.domain.AdRequest;
import com.adserve.serving.domain.ServableAd;
import com.adserve.serving.domain.ServableCampaign;
import com.adserve.shared.AdFormat;
import com.adserve.shared.AdId;
import com.adserve.shared.CampaignId;
import com.adserve.shared.CampaignSchedule;
import com.adserve.shared.Money;
import com.adserve.shared.TargetingRules;
import com.adserve.shared.ValidationException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static com.adserve.testsupport.TestData.NOW;
import static com.adserve.testsupport.TestData.PUBLISHER;
import static com.adserve.testsupport.TestData.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServableCampaignTest {
    private static final Instant START = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant END = Instant.parse("2026-11-30T00:00:00Z");

    private static ServableCampaign campaign(TargetingRules rules, AdFormat... formats) {
        List<ServableAd> ads = new java.util.ArrayList<>();
        for (AdFormat f : formats) {
            ads.add(new ServableAd(AdId.newId(), f, "h", "https://c.example.com/a.png", "https://e.example.com"));
        }
        return new ServableCampaign(CampaignId.newId(), Money.inr(100), new CampaignSchedule(START, END), rules, ads);
    }

    private static TargetingRules rules(Set<String> kw, Set<String> regions, Set<String> segments) {
        return new TargetingRules(kw, Set.of(), regions, segments);
    }

    private static AdRequest req(String region, Set<String> segments, String... keywords) {
        return new AdRequest(PUBLISHER, "r1", "https://example.com", Set.of(keywords), Set.of(), region,
                AdFormat.BANNER, segments);
    }

    // ELG-01
    @Test
    void scheduleBoundariesStartInclusiveEndExclusive() {
        ServableCampaign c = campaign(rules(Set.of("running"), Set.of(), Set.of()), AdFormat.BANNER);
        AdRequest r = request("r1", "running");
        assertFalse(c.isEligibleFor(r, START.minusSeconds(1)));
        assertTrue(c.isEligibleFor(r, START));
        assertTrue(c.isEligibleFor(r, END.minusSeconds(1)));
        assertFalse(c.isEligibleFor(r, END));
    }

    // ELG-02
    @Test
    void keywordMatchingFullPartialAndNone() {
        ServableCampaign c = campaign(rules(Set.of("running", "shoes"), Set.of(), Set.of()), AdFormat.BANNER);
        assertEquals(2, c.matchedTerms(request("r", "running", "shoes", "fitness")));
        assertEquals(1, c.matchedTerms(request("r", "running", "yoga")));
        assertEquals(0, c.matchedTerms(request("r", "yoga")));
        assertTrue(c.isEligibleFor(request("r", "running", "yoga"), NOW));
        assertFalse(c.isEligibleFor(request("r", "yoga"), NOW));
    }

    @Test
    void keywordsAreCaseInsensitive() {
        ServableCampaign c = campaign(rules(Set.of("Running"), Set.of(), Set.of()), AdFormat.BANNER);
        assertTrue(c.isEligibleFor(request("r", "RUNNING"), NOW));
    }

    // ELG-03
    @Test
    void regionRules() {
        ServableCampaign state = campaign(rules(Set.of("a"), Set.of("IN-KA"), Set.of()), AdFormat.BANNER);
        ServableCampaign country = campaign(rules(Set.of("a"), Set.of("IN"), Set.of()), AdFormat.BANNER);
        ServableCampaign anywhere = campaign(rules(Set.of("a"), Set.of(), Set.of()), AdFormat.BANNER);
        assertTrue(state.isEligibleFor(req("IN-KA", Set.of(), "a"), NOW));
        assertFalse(state.isEligibleFor(req("IN-MH", Set.of(), "a"), NOW));
        assertTrue(country.isEligibleFor(req("IN-MH", Set.of(), "a"), NOW));
        assertFalse(country.isEligibleFor(req("US-CA", Set.of(), "a"), NOW));
        assertTrue(anywhere.isEligibleFor(req("US-CA", Set.of(), "a"), NOW));
    }

    // ELG-04
    @Test
    void audienceRules() {
        ServableCampaign targeted = campaign(rules(Set.of("a"), Set.of(), Set.of("runners")), AdFormat.BANNER);
        ServableCampaign open = campaign(rules(Set.of("a"), Set.of(), Set.of()), AdFormat.BANNER);
        assertTrue(targeted.isEligibleFor(req("IN-KA", Set.of("runners", "x"), "a"), NOW));
        assertFalse(targeted.isEligibleFor(req("IN-KA", Set.of("cyclists"), "a"), NOW));
        assertFalse(targeted.isEligibleFor(req("IN-KA", Set.of(), "a"), NOW));
        assertTrue(open.isEligibleFor(req("IN-KA", Set.of(), "a"), NOW));
    }

    // ELG-05
    @Test
    void onlyAdsMatchingTheRequestedFormatAreUsed() {
        ServableCampaign c = campaign(rules(Set.of("a"), Set.of(), Set.of()), AdFormat.VIDEO);
        assertFalse(c.isEligibleFor(request("r", "a"), NOW));
        assertTrue(c.selectAd(AdFormat.VIDEO).isPresent());
    }

    @Test
    void selectedAdIsTheLowestIdAmongMatchingAds() {
        ServableCampaign c = campaign(rules(Set.of("a"), Set.of(), Set.of()), AdFormat.BANNER, AdFormat.BANNER, AdFormat.BANNER);
        AdId lowest = c.ads().stream().map(ServableAd::id).min(AdId::compareTo).orElseThrow();
        assertEquals(lowest, c.selectAd(AdFormat.BANNER).orElseThrow().id());
    }

    // SRV-12
    @Test
    void malformedRequestsAreRejected() {
        assertThrows(ValidationException.class, () -> new AdRequest("pub|1", "r", "u", Set.of("a"), Set.of(), "IN", AdFormat.BANNER, Set.of()));
        assertThrows(ValidationException.class, () -> new AdRequest(PUBLISHER, " ", "u", Set.of("a"), Set.of(), "IN", AdFormat.BANNER, Set.of()));
        assertThrows(ValidationException.class, () -> new AdRequest(PUBLISHER, "r", "u", Set.of("a"), Set.of(), "india", AdFormat.BANNER, Set.of()));
        assertThrows(ValidationException.class, () -> new AdRequest(PUBLISHER, "r", "u", Set.of("a"), Set.of(), null, AdFormat.BANNER, Set.of()));
        Set<String> tooMany = new java.util.HashSet<>();
        for (int i = 0; i < 51; i++) {
            tooMany.add("k" + i);
        }
        assertThrows(ValidationException.class, () -> new AdRequest(PUBLISHER, "r", "u", tooMany, Set.of(), "IN", AdFormat.BANNER, Set.of()));
    }
}
