package com.adserve.shared;

import java.util.Objects;
import java.util.UUID;

public record AdvertiserId(UUID value) implements Comparable<AdvertiserId> {
    public AdvertiserId {
        Objects.requireNonNull(value, "AdvertiserId value");
    }

    public static AdvertiserId newId() {
        return new AdvertiserId(UUID.randomUUID());
    }

    public static AdvertiserId of(String text) {
        try {
            return new AdvertiserId(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("invalid AdvertiserId: " + text);
        }
    }

    @Override
    public int compareTo(AdvertiserId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
