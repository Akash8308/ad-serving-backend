package com.adserve.shared;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Immutable targeting criteria. Keywords, categories and segments are lower-cased; regions are upper-cased.
 * Empty regions or segments mean "no restriction". A rule region "IN" matches a request region "IN-KA";
 * a rule region "IN-KA" matches only "IN-KA".
 */
public record TargetingRules(Set<String> keywords, Set<String> categories, Set<String> regions,
                             Set<String> audienceSegments) {
    private static final Pattern REGION = Pattern.compile("^[A-Z]{2}(-[A-Z0-9]{1,3})?$");

    public TargetingRules {
        keywords = normalize(keywords, false);
        categories = normalize(categories, false);
        regions = normalize(regions, true);
        audienceSegments = normalize(audienceSegments, false);
        if (keywords.isEmpty() && categories.isEmpty()) {
            throw new ValidationException("targeting needs at least one keyword or category");
        }
        for (String region : regions) {
            if (!REGION.matcher(region).matches()) {
                throw new ValidationException("invalid region code: " + region);
            }
        }
    }

    /** Count of request terms (keywords plus categories) that this rule set targets. Inputs must already be normalized. */
    public int matchedTerms(Set<String> requestKeywords, Set<String> requestCategories) {
        return countCommon(keywords, requestKeywords) + countCommon(categories, requestCategories);
    }

    public boolean matchesRegion(String requestRegion) {
        if (regions.isEmpty()) {
            return true;
        }
        if (requestRegion == null) {
            return false;
        }
        if (regions.contains(requestRegion)) {
            return true;
        }
        return requestRegion.length() >= 2 && regions.contains(requestRegion.substring(0, 2));
    }

    public boolean matchesAudience(Set<String> requestSegments) {
        if (audienceSegments.isEmpty()) {
            return true;
        }
        return countCommon(audienceSegments, requestSegments) > 0;
    }

    private static int countCommon(Set<String> a, Set<String> b) {
        int n = 0;
        for (String s : a) {
            if (b.contains(s)) {
                n++;
            }
        }
        return n;
    }

    private static Set<String> normalize(Collection<String> values, boolean upper) {
        if (values == null) {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) {
            if (v == null || v.isBlank()) {
                continue;
            }
            String t = v.trim();
            out.add(upper ? t.toUpperCase(Locale.ROOT) : t.toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(out);
    }
}
