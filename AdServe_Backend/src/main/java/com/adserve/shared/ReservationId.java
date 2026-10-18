package com.adserve.shared;

import java.util.Objects;
import java.util.UUID;

public record ReservationId(UUID value) implements Comparable<ReservationId> {
    public ReservationId {
        Objects.requireNonNull(value, "ReservationId value");
    }

    public static ReservationId newId() {
        return new ReservationId(UUID.randomUUID());
    }

    public static ReservationId of(String text) {
        try {
            return new ReservationId(UUID.fromString(text));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("invalid ReservationId: " + text);
        }
    }

    @Override
    public int compareTo(ReservationId other) {
        return value.compareTo(other.value);
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
