package com.adserve.testsupport;

import com.adserve.serving.domain.CandidateSource;
import com.adserve.serving.domain.CandidateSourceUnavailable;
import com.adserve.serving.domain.ServableCampaign;
import com.adserve.shared.AdFormat;

import java.util.ArrayList;
import java.util.List;

public final class InMemoryCandidateSource implements CandidateSource {
    private final List<ServableCampaign> campaigns = new ArrayList<>();
    public boolean unavailable;

    public void add(ServableCampaign campaign) {
        campaigns.add(campaign);
    }

    @Override
    public List<ServableCampaign> candidates(AdFormat format, String region) {
        if (unavailable) {
            throw new CandidateSourceUnavailable("simulated cache and database outage");
        }
        return List.copyOf(campaigns);
    }
}
