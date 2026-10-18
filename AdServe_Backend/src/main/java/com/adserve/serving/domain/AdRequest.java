package com.adserve.serving.domain;

import com.adserve.shared.AdFormat;
import com.adserve.shared.ValidationException;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Validated, normalized serve request. */
public record AdRequest(String publisherId, String requestId, String pageUrl, Set<String> keywords,
                        Set<String> categories, String region, AdFormat format, Set<String> audienceSegments) {
    private static final Pattern PUBLISHER_ID = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    private static final Pattern REGION = Pattern.compile("^[A-Z]{2}(-[A-Z0-9]{1,3})?$");
    private static final int MAX_TERMS = 50;

    public AdRequest {
        if (publisherId == null || !PUBLISHER_ID.matcher(publisherId).matches()) {
            throw new ValidationException("publisherId must match [A-Za-z0-9._-]{1,64}");
        }
        if (requestId == null || requestId.isBlank() || requestId.length() > 128) {
            throw new ValidationException("requestId must be 1-128 characters");
        }
        Objects.requireNonNull(format, "adFormat");
        keywords = normalize(keywords);
        categories = normalize(categories);
        audienceSegments = normalize(audienceSegments);
        if (keywords.size() + categories.size() > MAX_TERMS) {
            throw new ValidationException("too many keywords and categories (max " + MAX_TERMS + ")");
        }
        region = region == null ? null : region.trim().toUpperCase(Locale.ROOT);
        if (region == null || !REGION.matcher(region).matches()) {
            throw new ValidationException("region must look like IN or IN-KA");
        }
    }

    /** Number of distinct context terms; the denominator of relevance. */
    public int termCount() {
        return keywords.size() + categories.size();
    }

    private static Set<String> normalize(Collection<String> values) {
        if (values == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                out.add(v.trim().toLowerCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }
}
