package com.adserve.budget.domain;

public sealed interface ReservationResult {
    /** Funds reserved and the serve recorded. */
    record Reserved(Reservation reservation) implements ReservationResult { }

    /** Campaign not servable right now or not enough funds. Nothing was written. */
    record Rejected() implements ReservationResult { }

    /** The same (publisher, request) was already served; the original reservation is returned and nothing new is reserved. */
    record Duplicate(Reservation existing) implements ReservationResult { }
}
