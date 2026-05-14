package com.survey.controller;

import com.survey.dto.AnswerReminderRequest;
import com.survey.dto.ApiResponse;
import com.survey.entity.AnswerReminderRecord;
import com.survey.service.AnswerReminderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/reminders")
@RequiredArgsConstructor
public class AnswerReminderController {

    private final AnswerReminderService reminderService;

    @PostMapping("/create")
    public ApiResponse<List<AnswerReminderRecord>> createReminders(
            @Valid @RequestBody AnswerReminderRequest request) {
        List<AnswerReminderRecord> reminders = reminderService.createReminders(request);
        return ApiResponse.success("答卷提醒创建成功", reminders);
    }

    @PostMapping("/{reminderId}/send")
    public ApiResponse<Void> forceSendReminder(@PathVariable String reminderId) {
        reminderService.forceSendReminder(reminderId);
        return ApiResponse.success("提醒已发送", null);
    }

    @PostMapping("/{reminderId}/complete")
    public ApiResponse<Void> markReminderCompleted(@PathVariable String reminderId) {
        reminderService.markReminderCompleted(reminderId);
        return ApiResponse.success("提醒已标记为完成", null);
    }

    @GetMapping("/{reminderId}")
    public ApiResponse<AnswerReminderRecord> getReminder(@PathVariable String reminderId) {
        AnswerReminderRecord record = reminderService.getReminder(reminderId);
        return ApiResponse.success(record);
    }

    @GetMapping("/survey/{surveyId}")
    public ApiResponse<List<AnswerReminderRecord>> getRemindersBySurvey(@PathVariable String surveyId) {
        List<AnswerReminderRecord> records = reminderService.getRemindersBySurvey(surveyId);
        return ApiResponse.success(records);
    }

    @GetMapping("/publish/{publishId}")
    public ApiResponse<List<AnswerReminderRecord>> getRemindersByPublish(@PathVariable String publishId) {
        List<AnswerReminderRecord> records = reminderService.getRemindersByPublish(publishId);
        return ApiResponse.success(records);
    }

    @GetMapping("/survey/{surveyId}/pending")
    public ApiResponse<List<AnswerReminderRecord>> getPendingReminders(@PathVariable String surveyId) {
        List<AnswerReminderRecord> records = reminderService.getPendingReminders(surveyId);
        return ApiResponse.success(records);
    }

    @GetMapping("/survey/{surveyId}/completed")
    public ApiResponse<List<AnswerReminderRecord>> getCompletedReminders(@PathVariable String surveyId) {
        List<AnswerReminderRecord> records = reminderService.getCompletedReminders(surveyId);
        return ApiResponse.success(records);
    }

    @PostMapping("/survey/{surveyId}/check")
    public ApiResponse<Void> checkAndCompleteReminders(@PathVariable String surveyId) {
        reminderService.checkAndCompleteReminders(surveyId);
        return ApiResponse.success("提醒状态检查完成", null);
    }
}
