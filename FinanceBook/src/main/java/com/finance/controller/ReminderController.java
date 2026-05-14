package com.finance.controller;

import com.finance.dto.ApiResponse;
import com.finance.entity.Reminder;
import com.finance.service.ReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService reminderService;

    @GetMapping("/account/{accountId}")
    public ApiResponse<List<Reminder>> getRemindersByAccount(@PathVariable String accountId) {
        List<Reminder> reminders = reminderService.getRemindersByAccount(accountId);
        return ApiResponse.success(reminders);
    }

    @GetMapping("/type/{type}")
    public ApiResponse<List<Reminder>> getRemindersByType(@PathVariable String type) {
        List<Reminder> reminders = reminderService.getRemindersByType(type);
        return ApiResponse.success(reminders);
    }
}
