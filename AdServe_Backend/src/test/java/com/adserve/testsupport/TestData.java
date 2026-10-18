package com.adserve.testsupport;

import com.adserve.budget.domain.BudgetLimits;
import com.adserve.campaign.domain.Ad;
import com.adserve.campaign.domain.Campaign;
import com.adserve.serving.domain.AdRequest;
import com.adserve.serving.domain.ServableAd;
import com.adserve.serving.domain.ServableCampaign;
import com.adserve.shared.AdFormat;
import com.adserve.shared.AdId;
import com.adserve.shared.AdvertiserId;
import com.adserve.shared.CampaignId;
import com.adserve.shared.CampaignSchedule;
import com.adserve.shared.Money;
import com.adserve.shared.TargetingRules;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

public final class TestData {
    public static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    public static final AdvertiserId ADVERTISER = AdvertiserId.of("00000000-0000-0000-0000-0000000000a1");
    public static final AdvertiserId OTHER_ADVERTISER = AdvertiserId.of("00000000-0000-0000-0000-0000000000b2");
    public static final String PUBLISHER = "pub-101";

    private TestData() { }

    public static CampaignSchedule runningSchedule() {
        return new CampaignSchedule(NOW.minus(Duration.ofDays(1)), NOW.plus(Duration.ofDays(30)));
    }

    public static TargetingRules targeting(String... keywords) {
        return new TargetingRules(Set.of(keywords), Set.of(), Set.of(), Set.of());
    }

    public static Ad ad(AdFormat format) {
        return Ad.draft(format, "Find Your Next Running Shoes", "https://cdn.example.com/ad.png",
                "https://example.com/products/shoes");
    }

    public static Campaign draftCampaign(long bidMinor, String... keywords) {
        return Campaign.create(ADVERTISER, "Monsoon Running Shoes", Money.inr(bidMinor), runningSchedule(),
                targeting(keywords), List.of(ad(AdFormat.BANNER)));
    }

    public static Campaign activeCampaign(long bidMinor, String... keywords) {
        Campaign c = draftCampaign(bidMinor, keywords);
        c.activate(NOW);
        c.pullEvents();
        return c;
    }

    public static BudgetLimits limits(long total, long daily) {
        return new BudgetLimits(Money.inr(total), Money.inr(daily));
    }

    public static ServableCampaign servable(long bidMinor, String... keywords) {
        return servable(CampaignId.newId(), bidMinor, keywords);
    }

    public static ServableCampaign servable(CampaignId id, long bidMinor, String... keywords) {
        return new ServableCampaign(id, Money.inr(bidMinor), runningSchedule(), targeting(keywords),
                List.of(new ServableAd(AdId.newId(), AdFormat.BANNER, "Headline", "https://cdn.example.com/a.png",
                        "https://example.com/a")));
    }

    public static AdRequest request(String requestId, String... keywords) {
        return new AdRequest(PUBLISHER, requestId, "https://example.com/running-shoes", Set.of(keywords), Set.of(),
                "IN-KA", AdFormat.BANNER, Set.of());
    }
}
