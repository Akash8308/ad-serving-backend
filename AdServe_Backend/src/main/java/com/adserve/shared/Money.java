package com.adserve.shared;

import java.util.Objects;

/** Non-negative amount in minor units (paise). Single-currency system (assumption A2); currency is carried for safety. */
public record Money(long minor, String currency) implements Comparable<Money> {
    public static final String INR = "INR";

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (minor < 0) {
            throw new ValidationException("money cannot be negative");
        }
        if (currency.length() != 3) {
            throw new ValidationException("currency must be a 3-letter code");
        }
    }

    public static Money inr(long minor) {
        return new Money(minor, INR);
    }

    public static Money zeroInr() {
        return inr(0);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(Math.addExact(minor, other.minor), currency);
    }

    /** Subtraction that never goes below zero; callers must check {@link #isLessThan} first when that matters. */
    public Money minus(Money other) {
        requireSameCurrency(other);
        if (other.minor > minor) {
            throw new ValidationException("subtraction would make money negative");
        }
        return new Money(minor - other.minor, currency);
    }

    public boolean isPositive() {
        return minor > 0;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return minor < other.minor;
    }

    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return minor > other.minor;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return Long.compare(minor, other.minor);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new ValidationException("currency mismatch: " + currency + " vs " + other.currency);
        }
    }
}
