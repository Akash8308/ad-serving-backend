package com.adserve.shared;

import java.util.Objects;
import java.util.UUID;

public record AdId(UUID value) implements Comparable<AdId> {
    public AdId {
        Objects.requireNonNull(value, "AdId value");
    }

    public static AdId newId() {
        return new AdId(UUID.randomUUID());
    }

    public static AdId of(String text) {
        try {
            return new AdId(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("invalid AdId: " + text);
        }
    }

    @Override
    public int compareTo(AdId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
