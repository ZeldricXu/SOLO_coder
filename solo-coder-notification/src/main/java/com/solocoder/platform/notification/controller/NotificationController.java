package com.solocoder.platform.notification.controller;

import com.solocoder.platform.common.model.ApiResponse;
import com.solocoder.platform.notification.model.NotificationRequest;
import com.solocoder.platform.notification.model.NotificationResult;
import com.solocoder.platform.notification.model.NotificationTemplate;
import com.solocoder.platform.notification.service.NotificationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Collection;
import java.util.List;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationService notificationService;
    private final com.solocoder.platform.notification.config.NotificationConfigManager configManager;

    @PostMapping("/send")
    public ApiResponse<NotificationResult> send(@Valid @RequestBody NotificationRequest request) {
        return ApiResponse.success(notificationService.send(request));
    }

    @PostMapping("/send/batch")
    public ApiResponse<List<NotificationResult>> sendBatch(@Valid @RequestBody List<NotificationRequest> requests) {
        return ApiResponse.success(notificationService.sendBatch(requests));
    }

    @PostMapping("/templates")
    public ApiResponse<Void> registerTemplate(@Valid @RequestBody NotificationTemplate template) {
        notificationService.registerTemplate(template);
        return ApiResponse.success();
    }

    @GetMapping("/templates/{templateId}")
    public ApiResponse<NotificationTemplate> getTemplate(@PathVariable String templateId) {
        NotificationTemplate template = notificationService.getTemplate(templateId);
        if (template == null) {
            return ApiResponse.error(404, "Template not found: " + templateId);
        }
        return ApiResponse.success(template);
    }

    @GetMapping("/templates")
    public ApiResponse<Collection<NotificationTemplate>> getAllTemplates() {
        return ApiResponse.success((List<NotificationTemplate>) notificationService.getAllTemplates());
    }

    @GetMapping("/config")
    public ApiResponse<com.solocoder.platform.notification.config.NotificationDynamicConfig> getCurrentConfig() {
        return ApiResponse.success(configManager.getCurrentConfig());
    }

    @PostMapping("/config/reload")
    public ApiResponse<com.solocoder.platform.notification.config.NotificationDynamicConfig> reloadConfig() {
        return ApiResponse.success(configManager.reload());
    }

    @PutMapping("/config/channels/{channelType}")
    public ApiResponse<Void> updateChannelConfig(@PathVariable String channelType,
                                                  @RequestBody com.solocoder.platform.notification.config.ChannelConfig config) {
        configManager.updateChannelConfig(channelType, config);
        return ApiResponse.success();
    }

    @GetMapping("/config/channels/{channelType}")
    public ApiResponse<com.solocoder.platform.notification.config.ChannelConfig> getChannelConfig(@PathVariable String channelType) {
        return ApiResponse.success(configManager.getChannelConfig(channelType));
    }
}
