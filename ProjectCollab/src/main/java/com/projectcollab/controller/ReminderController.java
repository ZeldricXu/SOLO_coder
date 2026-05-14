package com.projectcollab.controller;

import com.projectcollab.dto.ApiResponse;
import com.projectcollab.entity.Reminder;
import com.projectcollab.service.reminder.ReminderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {

    @Autowired
    private ReminderService reminderService;

    @GetMapping("/project/{projectId}")
    public ApiResponse<List<Reminder>> getRemindersByProject(@PathVariable String projectId) {
        List<Reminder> reminders = reminderService.getRemindersByProjectId(projectId);
        return ApiResponse.success(reminders);
    }

    @GetMapping("/pending")
    public ApiResponse<List<Reminder>> getPendingReminders() {
        List<Reminder> reminders = reminderService.getPendingReminders();
        return ApiResponse.success(reminders);
    }

    @PostMapping("/{reminderId}/send")
    public ApiResponse<Void> markReminderAsSent(@PathVariable String reminderId) {
        reminderService.markReminderAsSent(reminderId);
        return ApiResponse.success(null);
    }
}
