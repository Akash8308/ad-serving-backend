package com.adserve.campaign.domain;

/** Derived, display-only view of whether a campaign can serve right now. */
public enum ServingState {
    DRAFT, PAUSED, COMPLETED, NOT_STARTED, EXPIRED, BUDGET_EXHAUSTED, DAILY_CAP_REACHED, SERVING
}
