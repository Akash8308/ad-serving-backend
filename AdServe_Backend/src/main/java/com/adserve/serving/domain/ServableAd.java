package com.adserve.serving.domain;

import com.adserve.shared.AdFormat;
import com.adserve.shared.AdId;

public record ServableAd(AdId id, AdFormat format, String headline, String creativeUrl, String destinationUrl) { }
