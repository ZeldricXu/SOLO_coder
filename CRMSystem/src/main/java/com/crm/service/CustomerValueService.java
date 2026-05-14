package com.crm.service;

import com.crm.entity.Customer;
import org.springframework.stereotype.Service;

@Service
public class CustomerValueService {

    private static final double HIGH_VALUE_THRESHOLD = 500000.0;
    private static final double MEDIUM_VALUE_THRESHOLD = 100000.0;

    public CustomerValue evaluateCustomerValue(Customer customer) {
        if (customer == null) {
            return CustomerValue.LOW;
        }

        int score = 0;

        if ("deal".equals(customer.getCustomerStatus())) {
            score += 50;
        } else if ("interested".equals(customer.getCustomerStatus())) {
            score += 30;
        }

        if (customer.getFollowCount() != null) {
            if (customer.getFollowCount() >= 10) {
                score += 20;
            } else if (customer.getFollowCount() >= 5) {
                score += 10;
            }
        }

        if (customer.getOpportunityCount() != null) {
            if (customer.getOpportunityCount() >= 3) {
                score += 20;
            } else if (customer.getOpportunityCount() >= 1) {
                score += 10;
            }
        }

        if ("enterprise".equals(customer.getCustomerType())) {
            score += 10;
        }

        if (score >= 70) {
            return CustomerValue.HIGH;
        } else if (score >= 40) {
            return CustomerValue.MEDIUM;
        }
        return CustomerValue.LOW;
    }

    public enum CustomerValue {
        HIGH,
        MEDIUM,
        LOW
    }

    public double getHighValueThreshold() {
        return HIGH_VALUE_THRESHOLD;
    }

    public double getMediumValueThreshold() {
        return MEDIUM_VALUE_THRESHOLD;
    }
}
