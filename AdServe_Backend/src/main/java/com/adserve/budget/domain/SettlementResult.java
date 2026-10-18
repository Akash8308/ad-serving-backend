package com.adserve.budget.domain;

public sealed interface SettlementResult {
    record Settled(Reservation reservation) implements SettlementResult { }

    /** Idempotent success: this reservation was already charged; nothing changed. */
    record AlreadySettled(Reservation reservation) implements SettlementResult { }

    record NotBillable(Reason reason) implements SettlementResult { }

    enum Reason { WINDOW_EXPIRED, UNKNOWN }
}
