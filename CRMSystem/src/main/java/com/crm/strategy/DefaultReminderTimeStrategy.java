package com.crm.strategy;

import com.crm.entity.Category;
import com.crm.entity.Customer;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

@Component
public class DefaultReminderTimeStrategy implements ReminderTimeStrategy {

    private static final long VIP_ADVANCE_HOURS = 48;
    private static final long REGULAR_ADVANCE_HOURS = 24;

    @Override
    public LocalDateTime calculateReminderTime(Customer customer, LocalDateTime baseTime, List<Category> categories) {
        if (isVIPCustomer(customer, categories)) {
            return baseTime.minusHours(VIP_ADVANCE_HOURS);
        }
        return baseTime.minusHours(REGULAR_ADVANCE_HOURS);
    }

    @Override
    public boolean isVIPCustomer(Customer customer, List<Category> categories) {
        if (categories == null || categories.isEmpty()) {
            return false;
        }
        return categories.stream()
                .anyMatch(category -> 
                    "VIP客户".equals(category.getCategoryName()) ||
                    "vip".equals(category.getCategoryName().toLowerCase()) ||
                    (category.getCategoryLevel() != null && category.getCategoryLevel() <= 1)
                );
    }

    public long getVIPAdvanceHours() {
        return VIP_ADVANCE_HOURS;
    }

    public long getRegularAdvanceHours() {
        return REGULAR_ADVANCE_HOURS;
    }
}
