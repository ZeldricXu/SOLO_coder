package com.metricplatform.event;

import com.metricplatform.dto.NotificationSendDTO;
import com.metricplatform.service.NotificationService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayEventListener {

    private final NotificationService notificationService;
    private final MeterRegistry meterRegistry;

    private final Map<GatewayEvent.EventType, Counter> eventCounters = new ConcurrentHashMap<>();
    private final Set<String> notifiedErrors = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final Set<String> NOTIFICATION_CHANNELS = Set.of("webhook", "email");
    private static final long NOTIFICATION_THROTTLE_MS = 60000;

    @EventListener
    public void onGatewayEvent(GatewayEvent event) {
        recordMetrics(event);
        logEvent(event);

        if (shouldNotify(event)) {
            sendNotification(event);
        }
    }

    @Async("notificationExecutor")
    @EventListener
    public void onGatewayEventAsync(GatewayEvent event) {
        if (event.getEventLevel() == GatewayEvent.EventLevel.CRITICAL ||
            event.getEventLevel() == GatewayEvent.EventLevel.ERROR) {
            log.info("异步处理网关事件: {} - {}", event.getEventId(), event.getEventType());
        }
    }

    private void recordMetrics(GatewayEvent event) {
        Counter counter = eventCounters.computeIfAbsent(event.getEventType(), type ->
                Counter.builder("gateway.events.total")
                        .tag("eventType", type.name())
                        .tag("eventLevel", event.getEventLevel().name())
                        .description("网关事件总数")
                        .register(meterRegistry)
        );
        counter.increment();
    }

    private void logEvent(GatewayEvent event) {
        String logMessage = String.format("网关事件 [%s] %s: %s | IP: %s | Path: %s %s | User: %s",
                event.getEventLevel(),
                event.getEventType(),
                event.getMessage(),
                event.getClientIp(),
                event.getMethod(),
                event.getPath(),
                event.getUser());

        switch (event.getEventLevel()) {
            case CRITICAL, ERROR -> log.error(logMessage);
            case WARN -> log.warn(logMessage);
            case INFO -> log.info(logMessage);
        }
    }

    private boolean shouldNotify(GatewayEvent event) {
        if (event.getEventLevel() == GatewayEvent.EventLevel.INFO) {
            return false;
        }

        String throttleKey = event.getEventType() + ":" + event.getClientIp();
        long now = System.currentTimeMillis();

        if (notifiedErrors.contains(throttleKey)) {
            return false;
        }

        notifiedErrors.add(throttleKey);

        new Thread(() -> {
            try {
                Thread.sleep(NOTIFICATION_THROTTLE_MS);
                notifiedErrors.remove(throttleKey);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }).start();

        return true;
    }

    private void sendNotification(GatewayEvent event) {
        try {
            NotificationSendDTO dto = buildNotification(event);
            notificationService.sendNotification(dto);
            log.info("网关事件通知已发送: {} - {}", event.getEventId(), event.getEventType());
        } catch (Exception e) {
            log.error("发送网关事件通知失败: {}", event.getEventId(), e);
        }
    }

    private NotificationSendDTO buildNotification(GatewayEvent event) {
        NotificationSendDTO dto = new NotificationSendDTO();

        Map<String, Object> templateParams = new HashMap<>();
        templateParams.put("eventId", event.getEventId());
        templateParams.put("eventType", event.getEventType());
        templateParams.put("eventLevel", event.getEventLevel());
        templateParams.put("message", event.getMessage());
        templateParams.put("clientIp", event.getClientIp());
        templateParams.put("path", event.getPath());
        templateParams.put("method", event.getMethod());
        templateParams.put("user", event.getUser() != null ? event.getUser() : "anonymous");
        templateParams.put("httpStatus", event.getHttpStatus());
        templateParams.put("timestamp", event.getTimestamp() != null ? event.getTimestamp().toString() : "");
        templateParams.put("metadata", event.getMetadata() != null ? event.getMetadata() : Collections.emptyMap());

        String levelPrefix = switch (event.getEventLevel()) {
            case CRITICAL -> "[严重]";
            case ERROR -> "[错误]";
            case WARN -> "[警告]";
            case INFO -> "[信息]";
        };

        String title = String.format("%s网关告警 - %s", levelPrefix, event.getEventType());
        String content = String.format("时间: %s\n事件: %s\n级别: %s\n描述: %s\nIP: %s\n路径: %s %s\n用户: %s\nHTTP状态: %s",
                event.getTimestamp(),
                event.getEventType(),
                event.getEventLevel(),
                event.getMessage(),
                event.getClientIp(),
                event.getMethod(),
                event.getPath(),
                event.getUser() != null ? event.getUser() : "anonymous",
                event.getHttpStatus());

        dto.setTemplateCode("GATEWAY_ALERT");
        dto.setChannels(new ArrayList<>(NOTIFICATION_CHANNELS));
        dto.setReceivers(List.of("admin@metricplatform.com", "ops@metricplatform.com"));
        dto.setTitle(title);
        dto.setContent(content);
        dto.setTemplateParams(templateParams);

        return dto;
    }
}
