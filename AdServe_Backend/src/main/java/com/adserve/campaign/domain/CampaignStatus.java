package com.adserve.campaign.domain;

/** Persisted lifecycle states. Exhaustion is derived (see ServingState), never stored. */
public enum CampaignStatus { DRAFT, ACTIVE, PAUSED, COMPLETED }
