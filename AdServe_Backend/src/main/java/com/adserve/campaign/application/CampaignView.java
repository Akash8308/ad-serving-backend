package com.adserve.campaign.application;

import com.adserve.budget.domain.BudgetView;
import com.adserve.campaign.domain.Campaign;
import com.adserve.campaign.domain.ServingState;

public record CampaignView(Campaign campaign, BudgetView budget, ServingState servingState) { }
