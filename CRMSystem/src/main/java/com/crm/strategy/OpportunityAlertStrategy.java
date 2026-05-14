package com.crm.strategy;

import com.crm.entity.Opportunity;

public interface OpportunityAlertStrategy {
    boolean shouldAlert(Opportunity opportunity);
    int getAlertThresholdDays(Opportunity opportunity);
}
