package com.adserve.serving.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Replaceable selection strategy: filters ineligible candidates and returns the rest, best first. */
public interface AdSelectionPolicy {
    List<RankedCandidate> rank(AdRequest request, Collection<ServableCampaign> candidates, Instant now);
}
