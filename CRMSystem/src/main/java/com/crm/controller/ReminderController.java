package com.crm.controller;

import com.crm.common.ApiResponse;
import com.crm.entity.Reminder;
import com.crm.service.ReminderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {

    @Autowired
    private ReminderService reminderService;

    @GetMapping("/customer/{customerId}")
    public ApiResponse<List<Reminder>> getCustomerReminders(@PathVariable String customerId) {
        List<Reminder> reminders = reminderService.getCustomerReminders(customerId);
        return ApiResponse.success(reminders);
    }

    @GetMapping("/sales/{salesId}")
    public ApiResponse<List<Reminder>> getSalesReminders(@PathVariable String salesId) {
        List<Reminder> reminders = reminderService.getSalesReminders(salesId);
        return ApiResponse.success(reminders);
    }

    @GetMapping("/pending")
    public ApiResponse<List<Reminder>> getPendingReminders() {
        List<Reminder> reminders = reminderService.getPendingReminders();
        return ApiResponse.success(reminders);
    }
}
