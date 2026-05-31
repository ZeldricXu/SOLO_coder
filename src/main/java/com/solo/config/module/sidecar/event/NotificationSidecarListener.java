package com.solo.config.module.sidecar.event;

import com.solo.config.entity.Notification;
import com.solo.config.module.notification.NotificationService;
import com.solo.config.module.sidecar.SidecarProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationSidecarListener implements SidecarEventListener {

    private final NotificationService notificationService;
    private final SidecarProperties properties;

    @Override
    @EventListener
    @Async
    public void onSidecarEvent(SidecarEvent event) {
        if (!properties.getEvents().isEnabled() || !properties.getEvents().getNotification().isEnabled()) {
            return;
        }

        log.info("Received sidecar event: {} for instance: {}", event.getEventType(), event.getInstanceId());

        String title = buildNotificationTitle(event);
        String content = buildNotificationContent(event);
        String priority = determinePriority(event);

        Notification notification = new Notification();
        notification.setTitle(title);
        notification.setContent(content);
        notification.setPriority(priority);
        notification.setChannel(properties.getEvents().getNotification().getChannels());
        notification.setTags(Map.of(
                "eventType", event.getEventType(),
                "instanceId", event.getInstanceId(),
                "podName", event.getPodName(),
                "namespace", event.getNamespace()
        ));

        notificationService.sendNotification(notification).subscribe(
                result -> log.debug("Notification sent for sidecar event: {}", event.getEventType()),
                error -> log.error("Failed to send notification for sidecar event: {}", event.getEventType(), error)
        );
    }

    private String buildNotificationTitle(SidecarEvent event) {
        return switch (event.getEventType()) {
            case "SIDECAR_INJECTED" -> "Sidecar 注入成功";
            case "SIDECAR_CONFIG_UPDATED" -> "Sidecar 配置更新";
            case "SIDECAR_REMOVED" -> "Sidecar 已移除";
            case "SIDECAR_UNHEALTHY" -> "Sidecar 健康状态异常";
            case "SIDECAR_HEALTHY" -> "Sidecar 健康状态恢复";
            default -> "Sidecar 事件通知";
        };
    }

    private String buildNotificationContent(SidecarEvent event) {
        StringBuilder content = new StringBuilder();
        content.append("实例ID: ").append(event.getInstanceId()).append("\n");
        content.append("Pod名称: ").append(event.getPodName()).append("\n");
        content.append("命名空间: ").append(event.getNamespace()).append("\n");
        content.append("事件时间: ").append(event.getTimestamp()).append("\n");

        if (event.getPayload() != null && !event.getPayload().isEmpty()) {
            content.append("详细信息: ").append(event.getPayload());
        }

        return content.toString();
    }

    private String determinePriority(SidecarEvent event) {
        return switch (event.getEventType()) {
            case "SIDECAR_UNHEALTHY" -> "HIGH";
            case "SIDECAR_CONFIG_UPDATED", "SIDECAR_INJECTED" -> "MEDIUM";
            default -> "LOW";
        };
    }
}
