package com.adserve.campaign.domain;

import com.adserve.shared.AdFormat;
import com.adserve.shared.AdId;
import com.adserve.shared.ValidationException;

import java.net.URI;
import java.util.Objects;

/** Creative belonging to exactly one campaign (invariant I-C5). */
public record Ad(AdId id, AdFormat format, String headline, String creativeUrl, String destinationUrl) {
    public Ad {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(format, "format");
        if (headline == null || headline.isBlank() || headline.length() > 200) {
            throw new ValidationException("ad headline must be 1-200 characters");
        }
        requireHttps(creativeUrl, "creativeUrl");
        requireHttps(destinationUrl, "destinationUrl");
    }

    public static Ad draft(AdFormat format, String headline, String creativeUrl, String destinationUrl) {
        return new Ad(AdId.newId(), format, headline, creativeUrl, destinationUrl);
    }

    private static void requireHttps(String url, String field) {
        try {
            URI uri = URI.create(url == null ? "" : url);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                throw new ValidationException(field + " must be an https URL");
            }
        } catch (IllegalArgumentException e) {
            throw new ValidationException(field + " must be an https URL");
        }
    }
}
