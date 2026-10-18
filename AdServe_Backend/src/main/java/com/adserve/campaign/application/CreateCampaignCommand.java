package com.adserve.campaign.application;

import com.adserve.budget.domain.BudgetLimits;
import com.adserve.campaign.domain.Ad;
import com.adserve.shared.AdvertiserId;
import com.adserve.shared.CampaignSchedule;
import com.adserve.shared.Money;
import com.adserve.shared.TargetingRules;

import java.util.List;

public record CreateCampaignCommand(AdvertiserId advertiserId, String name, Money bid, BudgetLimits budget,
                                    CampaignSchedule schedule, TargetingRules targeting, List<Ad> ads) { }
