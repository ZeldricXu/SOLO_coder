package com.crm.strategy;

import com.crm.config.OpportunityAlertProperties;
import com.crm.entity.Opportunity;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
public class DynamicOpportunityAlertStrategy implements OpportunityAlertStrategy {

    private final OpportunityAlertProperties properties;

    public DynamicOpportunityAlertStrategy(OpportunityAlertProperties properties) {
        this.properties = properties;
    }

    @Override
    public int getAlertThresholdDays(Opportunity opportunity) {
        if (opportunity == null || opportunity.getOpportunityAmount() == null) {
            return properties.getSmallAmount().getAlertDays();
        }

        double amount = opportunity.getOpportunityAmount();
        
        if (amount >= properties.getLargeAmount().getThreshold()) {
            return properties.getLargeAmount().getAlertDays();
        } else if (amount >= properties.getMediumAmount().getThreshold()) {
            return properties.getMediumAmount().getAlertDays();
        } else {
            return properties.getSmallAmount().getAlertDays();
        }
    }

    @Override
    public boolean shouldAlert(Opportunity opportunity) {
        if (opportunity == null) {
            return false;
        }

        String status = opportunity.getOpportunityStatus();
        if ("success".equalsIgnoreCase(status) || "failed".equalsIgnoreCase(status)) {
            return false;
        }

        LocalDateTime updatedAt = opportunity.getUpdatedAt();
        if (updatedAt == null) {
            return false;
        }

        int thresholdDays = getAlertThresholdDays(opportunity);
        long daysSinceUpdate = Duration.between(updatedAt, LocalDateTime.now()).toDays();

        return daysSinceUpdate >= thresholdDays;
    }

    public String getOpportunityAmountCategory(Opportunity opportunity) {
        if (opportunity == null || opportunity.getOpportunityAmount() == null) {
            return "SMALL";
        }

        double amount = opportunity.getOpportunityAmount();
        
        if (amount >= properties.getLargeAmount().getThreshold()) {
            return "LARGE";
        } else if (amount >= properties.getMediumAmount().getThreshold()) {
            return "MEDIUM";
        } else {
            return "SMALL";
        }
    }

    public double getLargeAmountThreshold() {
        return properties.getLargeAmount().getThreshold();
    }

    public int getLargeAmountAlertDays() {
        return properties.getLargeAmount().getAlertDays();
    }

    public double getMediumAmountThreshold() {
        return properties.getMediumAmount().getThreshold();
    }

    public int getMediumAmountAlertDays() {
        return properties.getMediumAmount().getAlertDays();
    }

    public int getSmallAmountAlertDays() {
        return properties.getSmallAmount().getAlertDays();
    }
}
