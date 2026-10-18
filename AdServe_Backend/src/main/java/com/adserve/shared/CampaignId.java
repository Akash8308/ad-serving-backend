package com.adserve.shared;

import java.util.Objects;
import java.util.UUID;

public record CampaignId(UUID value) implements Comparable<CampaignId> {
    public CampaignId {
        Objects.requireNonNull(value, "CampaignId value");
    }

    public static CampaignId newId() {
        return new CampaignId(UUID.randomUUID());
    }

    public static CampaignId of(String text) {
        try {
            return new CampaignId(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("invalid CampaignId: " + text);
        }
    }

    @Override
    public int compareTo(CampaignId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
