package com.healthtrack.controller;

import com.healthtrack.dto.ApiResponse;
import com.healthtrack.entity.HealthReminder;
import com.healthtrack.service.ReminderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reminders")
public class ReminderController {

    @Autowired
    private ReminderService reminderService;

    @PostMapping
    public ResponseEntity<ApiResponse<HealthReminder>> createReminder(@RequestBody HealthReminder reminder) {
        try {
            HealthReminder created = reminderService.createReminder(reminder);
            return ResponseEntity.ok(ApiResponse.success(created));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "创建提醒失败: " + e.getMessage()));
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<HealthReminder>>> getUserReminders(@RequestParam String userId) {
        try {
            List<HealthReminder> reminders = reminderService.getUserReminders(userId);
            return ResponseEntity.ok(ApiResponse.success(reminders));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "查询提醒失败: " + e.getMessage()));
        }
    }

    @GetMapping("/{reminderId}")
    public ResponseEntity<ApiResponse<HealthReminder>> getReminderById(@PathVariable String reminderId) {
        try {
            return reminderService.getReminderById(reminderId)
                    .map(reminder -> ResponseEntity.ok(ApiResponse.success(reminder)))
                    .orElse(ResponseEntity.notFound().build());
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "查询提醒失败: " + e.getMessage()));
        }
    }

    @PutMapping("/{reminderId}")
    public ResponseEntity<ApiResponse<HealthReminder>> updateReminder(@PathVariable String reminderId, @RequestBody HealthReminder reminder) {
        try {
            HealthReminder updated = reminderService.updateReminder(reminderId, reminder);
            return ResponseEntity.ok(ApiResponse.success(updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error(400, e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "更新提醒失败: " + e.getMessage()));
        }
    }

    @DeleteMapping("/{reminderId}")
    public ResponseEntity<ApiResponse<Void>> deleteReminder(@PathVariable String reminderId) {
        try {
            reminderService.deleteReminder(reminderId);
            return ResponseEntity.ok(ApiResponse.success(null));
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(ApiResponse.error(500, "删除提醒失败: " + e.getMessage()));
        }
    }
}
