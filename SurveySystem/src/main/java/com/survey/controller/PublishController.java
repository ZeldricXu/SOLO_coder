package com.survey.controller;

import com.survey.dto.ApiResponse;
import com.survey.dto.PublishConfirmRequest;
import com.survey.dto.PublishRequest;
import com.survey.dto.PublishResponse;
import com.survey.entity.PublishRecord;
import com.survey.service.PublishService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/surveys")
@RequiredArgsConstructor
public class PublishController {

    private final PublishService publishService;

    @PostMapping("/publish")
    public ApiResponse<PublishResponse> publishSurvey(@Valid @RequestBody PublishRequest request) {
        PublishResponse response = publishService.publishSurvey(request);
        return ApiResponse.success("问卷发布请求已提交", response);
    }

    @PostMapping("/publish/confirm")
    public ApiResponse<PublishRecord> confirmPublish(@Valid @RequestBody PublishConfirmRequest request) {
        PublishRecord record = publishService.confirmPublish(request);
        return ApiResponse.success("发布确认完成", record);
    }

    @PostMapping("/publish/{publishId}/resend")
    public ApiResponse<Void> resendNotification(@PathVariable String publishId) {
        publishService.resendNotification(publishId);
        return ApiResponse.success("发布通知已重新发送", null);
    }

    @PostMapping("/publish/{publishId}/cancel")
    public ApiResponse<Void> cancelPublish(@PathVariable String publishId) {
        publishService.cancelPublish(publishId);
        return ApiResponse.success("发布已取消", null);
    }

    @GetMapping("/publish/{publishId}")
    public ApiResponse<PublishRecord> getPublishRecord(@PathVariable String publishId) {
        PublishRecord record = publishService.getPublishRecord(publishId);
        return ApiResponse.success(record);
    }

    @GetMapping("/{surveyId}/publish")
    public ApiResponse<List<PublishRecord>> getPublishRecords(@PathVariable String surveyId) {
        List<PublishRecord> records = publishService.getPublishRecordsBySurvey(surveyId);
        return ApiResponse.success(records);
    }

    @GetMapping("/{surveyId}/publish/active")
    public ApiResponse<List<PublishRecord>> getActivePublishRecords(@PathVariable String surveyId) {
        List<PublishRecord> records = publishService.getActivePublishRecords(surveyId);
        return ApiResponse.success(records);
    }

    @GetMapping("/publish/pending")
    public ApiResponse<List<PublishRecord>> getPendingConfirmRecords() {
        List<PublishRecord> records = publishService.getPendingConfirmRecords();
        return ApiResponse.success(records);
    }

    @GetMapping("/{surveyId}/publish/confirmed")
    public ApiResponse<List<PublishRecord>> getConfirmedPublishRecords(@PathVariable String surveyId) {
        List<PublishRecord> records = publishService.getConfirmedPublishRecords(surveyId);
        return ApiResponse.success(records);
    }
}
