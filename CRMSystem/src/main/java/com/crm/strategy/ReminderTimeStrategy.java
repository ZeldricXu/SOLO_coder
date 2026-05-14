package com.crm.strategy;

import com.crm.entity.Category;
import com.crm.entity.Customer;

import java.time.LocalDateTime;
import java.util.List;

public interface ReminderTimeStrategy {
    LocalDateTime calculateReminderTime(Customer customer, LocalDateTime baseTime, List<Category> categories);
    boolean isVIPCustomer(Customer customer, List<Category> categories);
}
