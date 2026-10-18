package com.adserve.budget.domain;

import com.adserve.shared.AdId;

/** Immutable copy of the creative that was served; stored on the reservation so retries and clicks are attributable. */
public record AdSnapshot(AdId adId, String headline, String creativeUrl, String destinationUrl) { }
