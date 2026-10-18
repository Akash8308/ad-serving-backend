package com.adserve.serving.domain;

public record RankedCandidate(ServableCampaign campaign, ServableAd ad, long score, int relevanceBp) { }
