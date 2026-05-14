package com.crm.strategy;

import com.crm.entity.Opportunity;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

@Component
public class DefaultOpportunityAlertStrategy implements OpportunityAlertStrategy {

    private static final double LARGE_AMOUNT_THRESHOLD = 100000.0;
    private static final int LARGE_AMOUNT_ALERT_DAYS = 3;
    private static final int SMALL_AMOUNT_ALERT_DAYS = 7;

    @Override
    public boolean shouldAlert(Opportunity opportunity) {
        if (!"following".equals(opportunity.getOpportunityStatus())) {
            return false;
        }
        if (opportunity.getUpdatedAt() == null) {
            return false;
        }
        long daysSinceUpdate = ChronoUnit.DAYS.between(opportunity.getUpdatedAt(), LocalDateTime.now());
        int threshold = getAlertThresholdDays(opportunity);
        return daysSinceUpdate >= threshold;
    }

    @Override
    public int getAlertThresholdDays(Opportunity opportunity) {
        if (opportunity.getOpportunityAmount() != null && 
            opportunity.getOpportunityAmount() >= LARGE_AMOUNT_THRESHOLD) {
            return LARGE_AMOUNT_ALERT_DAYS;
        }
        return SMALL_AMOUNT_ALERT_DAYS;
    }

    public double getLargeAmountThreshold() {
        return LARGE_AMOUNT_THRESHOLD;
    }

    public int getLargeAmountAlertDays() {
        return LARGE_AMOUNT_ALERT_DAYS;
    }

    public int getSmallAmountAlertDays() {
        return SMALL_AMOUNT_ALERT_DAYS;
    }
}
