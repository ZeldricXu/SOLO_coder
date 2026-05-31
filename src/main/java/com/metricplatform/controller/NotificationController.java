package com.metricplatform.controller;

import com.metricplatform.common.ApiResponse;
import com.metricplatform.dto.NotificationSendDTO;
import com.metricplatform.dto.NotificationTemplateDTO;
import com.metricplatform.entity.SysNotificationRecord;
import com.metricplatform.entity.SysNotificationTemplate;
import com.metricplatform.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;

    @GetMapping("/channels")
    public Mono<ApiResponse<Set<String>>> getSupportedChannels() {
        return Mono.just(ApiResponse.success(notificationService.getSupportedChannels()));
    }

    @GetMapping("/templates")
    public Mono<ApiResponse<List<SysNotificationTemplate>>> getAllTemplates() {
        return Mono.just(ApiResponse.success(notificationService.getAllTemplates()));
    }

    @GetMapping("/templates/{templateId}")
    public Mono<ApiResponse<SysNotificationTemplate>> getTemplate(@PathVariable String templateId) {
        SysNotificationTemplate template = notificationService.getById(templateId);
        if (template != null) {
            return Mono.just(ApiResponse.success(template));
        } else {
            return Mono.just(ApiResponse.notFound("模板不存在"));
        }
    }

    @PostMapping("/templates")
    public Mono<ApiResponse<SysNotificationTemplate>> createTemplate(
            @Valid @RequestBody NotificationTemplateDTO dto) {
        SysNotificationTemplate template = notificationService.createTemplate(dto);
        return Mono.just(ApiResponse.created(template));
    }

    @PutMapping("/templates/{templateId}")
    public Mono<ApiResponse<SysNotificationTemplate>> updateTemplate(
            @PathVariable String templateId,
            @Valid @RequestBody NotificationTemplateDTO dto) {
        try {
            SysNotificationTemplate template = notificationService.updateTemplate(templateId, dto);
            return Mono.just(ApiResponse.success(template));
        } catch (IllegalArgumentException e) {
            return Mono.just(ApiResponse.notFound(e.getMessage()));
        } catch (IllegalStateException e) {
            return Mono.just(ApiResponse.validationError(e.getMessage()));
        }
    }

    @DeleteMapping("/templates/{templateId}")
    public Mono<ApiResponse<Void>> deleteTemplate(@PathVariable String templateId) {
        boolean result = notificationService.deleteTemplate(templateId);
        if (result) {
            return Mono.just(ApiResponse.success(null));
        } else {
            return Mono.just(ApiResponse.notFound("模板不存在"));
        }
    }

    @PostMapping("/send")
    public Mono<ApiResponse<Map<String, Object>>> sendNotification(
            @Valid @RequestBody NotificationSendDTO dto) {
        try {
            SysNotificationRecord record = notificationService.sendNotification(dto);
            Map<String, Object> result = new HashMap<>();
            result.put("recordId", record.getRecordId());
            result.put("channel", record.getChannel());
            result.put("receiver", record.getReceiver());
            result.put("status", dto.isAsync() ? "queued" : record.getStatus());
            result.put("async", dto.isAsync());
            return Mono.just(ApiResponse.success(result));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return Mono.just(ApiResponse.validationError(e.getMessage()));
        }
    }

    @GetMapping("/records/{recordId}")
    public Mono<ApiResponse<SysNotificationRecord>> getRecord(@PathVariable String recordId) {
        SysNotificationRecord record = notificationService.getRecord(recordId);
        if (record != null) {
            return Mono.just(ApiResponse.success(record));
        } else {
            return Mono.just(ApiResponse.notFound("记录不存在"));
        }
    }

    @GetMapping("/records")
    public Mono<ApiResponse<List<SysNotificationRecord>>> getRecords(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "100") int limit) {
        List<SysNotificationRecord> records = notificationService.getRecords(channel, status, limit);
        return Mono.just(ApiResponse.success(records));
    }
}
