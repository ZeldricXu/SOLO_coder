package com.crm.strategy;

import com.crm.config.ReminderTimeProperties;
import com.crm.entity.Category;
import com.crm.entity.Customer;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class DynamicReminderTimeStrategy implements ReminderTimeStrategy {

    private final ReminderTimeProperties properties;

    public DynamicReminderTimeStrategy(ReminderTimeProperties properties) {
        this.properties = properties;
    }

    @Override
    public LocalDateTime calculateReminderTime(Customer customer, LocalDateTime baseTime, List<Category> categories) {
        int advanceHours = getAdvanceHours(customer, categories);
        return baseTime.minusHours(advanceHours);
    }

    @Override
    public boolean isVIPCustomer(Customer customer, List<Category> categories) {
        if (categories != null) {
            for (Category category : categories) {
                if ("VIP".equalsIgnoreCase(category.getCategoryName()) ||
                    "VIP".equalsIgnoreCase(category.getCategoryCode()) ||
                    (category.getCategoryLevel() != null && category.getCategoryLevel() == 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    public int getAdvanceHours(Customer customer, List<Category> categories) {
        String customerValue = evaluateCustomerValue(customer, categories);
        return switch (customerValue) {
            case "HIGH" -> properties.getHighValue().getAdvanceHours();
            case "MEDIUM" -> properties.getMediumValue().getAdvanceHours();
            default -> properties.getLowValue().getAdvanceHours();
        };
    }

    private String evaluateCustomerValue(Customer customer, List<Category> categories) {
        if (isVIPCustomer(customer, categories)) {
            return "HIGH";
        }

        if (customer != null) {
            String customerType = customer.getCustomerType();
            if ("VIP".equalsIgnoreCase(customerType) || "ENTERPRISE".equalsIgnoreCase(customerType)) {
                return "HIGH";
            }
            if ("GOVERNMENT".equalsIgnoreCase(customerType) || "PARTNER".equalsIgnoreCase(customerType)) {
                return "MEDIUM";
            }
            if ("closed".equalsIgnoreCase(customer.getCustomerStatus())) {
                return "HIGH";
            }
            if ("interested".equalsIgnoreCase(customer.getCustomerStatus())) {
                return "MEDIUM";
            }
        }
        return "LOW";
    }

    public int getHighValueAdvanceHours() {
        return properties.getHighValue().getAdvanceHours();
    }

    public int getMediumValueAdvanceHours() {
        return properties.getMediumValue().getAdvanceHours();
    }

    public int getLowValueAdvanceHours() {
        return properties.getLowValue().getAdvanceHours();
    }
}
