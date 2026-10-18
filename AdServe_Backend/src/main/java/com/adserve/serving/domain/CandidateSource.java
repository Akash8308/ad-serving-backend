package com.adserve.serving.domain;

import com.adserve.shared.AdFormat;

import java.util.List;

/** Hint source for candidate campaigns (Redis, falling back to the database). Never an authority on eligibility. */
public interface CandidateSource {
    /** @throws CandidateSourceUnavailable when no source (cache or database) can answer in time */
    List<ServableCampaign> candidates(AdFormat format, String region);
}
