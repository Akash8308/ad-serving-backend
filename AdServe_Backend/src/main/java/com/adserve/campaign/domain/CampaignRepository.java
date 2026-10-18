package com.adserve.campaign.domain;

import com.adserve.shared.CampaignId;

import java.util.Optional;

public interface CampaignRepository {
    /**
     * Inserts a new campaign or updates an existing one using optimistic locking.
     * @throws ConcurrentModification if the stored version differs from the version the caller loaded
     * @return the saved campaign carrying its new version
     */
    Campaign save(Campaign campaign);

    Optional<Campaign> findById(CampaignId id);
}
