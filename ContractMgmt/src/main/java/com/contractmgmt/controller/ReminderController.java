package com.contractmgmt.controller;

import com.contractmgmt.dto.ApiResponse;
import com.contractmgmt.entity.ReminderConfig;
import com.contractmgmt.service.ReminderService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {

    private final ReminderService reminderService;

    public ReminderController(ReminderService reminderService) {
        this.reminderService = reminderService;
    }

    @GetMapping("/contract/{contractId}")
    public ApiResponse<List<ReminderConfig>> getRemindersByContract(
            @PathVariable String contractId) {
        List<ReminderConfig> reminders = reminderService.getRemindersByContract(contractId);
        return ApiResponse.success(reminders);
    }

    @GetMapping("/pending")
    public ApiResponse<List<ReminderConfig>> getPendingReminders() {
        List<ReminderConfig> reminders = reminderService.getPendingReminders();
        return ApiResponse.success(reminders);
    }

    @PostMapping("/check")
    public ApiResponse<String> checkReminders() {
        reminderService.checkAndSendReminders();
        return ApiResponse.success("提醒检测执行完成", null);
    }
}
