package com.meeting.controller;

import com.meeting.dto.ApiResponse;
import com.meeting.dto.ReminderSendRequest;
import com.meeting.entity.Reminder;
import com.meeting.service.ReminderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reminders")
@RequiredArgsConstructor
public class ReminderController {

    private final ReminderService reminderService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<Reminder>>> getAllReminders() {
        List<Reminder> reminders = reminderService.getAllReminders();
        return ResponseEntity.ok(ApiResponse.success(reminders));
    }

    @GetMapping("/{reminderId}")
    public ResponseEntity<ApiResponse<Reminder>> getReminderById(@PathVariable String reminderId) {
        Reminder reminder = reminderService.getReminderById(reminderId);
        return ResponseEntity.ok(ApiResponse.success(reminder));
    }

    @GetMapping("/meeting/{meetingId}")
    public ResponseEntity<ApiResponse<List<Reminder>>> getRemindersByMeeting(@PathVariable String meetingId) {
        List<Reminder> reminders = reminderService.getRemindersByMeetingId(meetingId);
        return ResponseEntity.ok(ApiResponse.success(reminders));
    }

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<Reminder>>> getPendingReminders() {
        List<Reminder> reminders = reminderService.getPendingReminders();
        return ResponseEntity.ok(ApiResponse.success(reminders));
    }

    @PostMapping("/send")
    public ResponseEntity<ApiResponse<Void>> sendReminder(@RequestBody ReminderSendRequest request) {
        reminderService.sendReminder(request);
        return ResponseEntity.ok(ApiResponse.success("提醒发送成功", null));
    }

    @DeleteMapping("/{reminderId}")
    public ResponseEntity<ApiResponse<Void>> deleteReminder(@PathVariable String reminderId) {
        reminderService.deleteReminder(reminderId);
        return ResponseEntity.ok(ApiResponse.success("提醒删除成功", null));
    }
}
