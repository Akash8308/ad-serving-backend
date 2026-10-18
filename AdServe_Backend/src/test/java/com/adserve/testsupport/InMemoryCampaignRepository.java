package com.adserve.testsupport;

import com.adserve.campaign.domain.Campaign;
import com.adserve.campaign.domain.CampaignRepository;
import com.adserve.campaign.domain.ConcurrentModification;
import com.adserve.shared.CampaignId;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/** Optimistic-locking fake: saving a stale copy fails exactly like JPA @Version would. */
public final class InMemoryCampaignRepository implements CampaignRepository {
    private final Map<CampaignId, Campaign> store = new HashMap<>();

    @Override
    public synchronized Campaign save(Campaign campaign) {
        Campaign stored = store.get(campaign.id());
        if (stored == null) {
            store.put(campaign.id(), campaign.withVersion(campaign.version()));
            return campaign.withVersion(campaign.version());
        }
        if (stored.version() != campaign.version()) {
            throw new ConcurrentModification("campaign " + campaign.id() + " was modified concurrently");
        }
        Campaign next = campaign.withVersion(campaign.version() + 1);
        store.put(campaign.id(), next);
        return next.withVersion(next.version());
    }

    @Override
    public synchronized Optional<Campaign> findById(CampaignId id) {
        Campaign stored = store.get(id);
        return stored == null ? Optional.empty() : Optional.of(stored.withVersion(stored.version()));
    }
}
