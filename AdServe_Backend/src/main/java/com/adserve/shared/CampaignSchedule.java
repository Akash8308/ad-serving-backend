package com.adserve.shared;

import java.time.Instant;
import java.util.Objects;

/** startAt is inclusive, endAt is exclusive (rule 2). */
public record CampaignSchedule(Instant startAt, Instant endAt) {
    public CampaignSchedule {
        Objects.requireNonNull(startAt, "startAt");
        Objects.requireNonNull(endAt, "endAt");
        if (!endAt.isAfter(startAt)) {
            throw new ValidationException("schedule end must be after start");
        }
    }

    public boolean isActiveAt(Instant now) {
        return !now.isBefore(startAt) && now.isBefore(endAt);
    }

    public boolean hasStartedAt(Instant now) {
        return !now.isBefore(startAt);
    }

    public boolean hasEndedAt(Instant now) {
        return !now.isBefore(endAt);
    }
}
