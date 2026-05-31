package com.metricplatform.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class GatewayEventPublisher {

    private final ApplicationEventPublisher eventPublisher;

    public void publishEvent(GatewayEvent event) {
        event.setEventId("evt_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
        if (event.getTimestamp() == null) {
            event.setTimestamp(LocalDateTime.now());
        }

        log.debug("发布网关事件: {} - {} - {}", event.getEventId(), event.getEventType(), event.getMessage());
        eventPublisher.publishEvent(event);
    }

    public void publishAuthFailure(String clientIp, String path, String method, String message, Map<String, Object> metadata) {
        GatewayEvent event = GatewayEvent.builder()
                .eventType(GatewayEvent.EventType.AUTH_FAILURE)
                .eventLevel(GatewayEvent.EventLevel.WARN)
                .source("gateway")
                .message(message)
                .clientIp(clientIp)
                .path(path)
                .method(method)
                .httpStatus(401)
                .metadata(metadata)
                .build();
        publishEvent(event);
    }

    public void publishRateLimitTriggered(String clientIp, String path, String method, String user, Map<String, Object> metadata) {
        GatewayEvent event = GatewayEvent.builder()
                .eventType(GatewayEvent.EventType.RATE_LIMIT_TRIGGERED)
                .eventLevel(GatewayEvent.EventLevel.WARN)
                .source("gateway")
                .message("请求频率超限")
                .clientIp(clientIp)
                .path(path)
                .method(method)
                .user(user)
                .httpStatus(429)
                .metadata(metadata)
                .build();
        publishEvent(event);
    }

    public void publishAuthSuccess(String clientIp, String path, String method, String user, Map<String, Object> metadata) {
        GatewayEvent event = GatewayEvent.builder()
                .eventType(GatewayEvent.EventType.AUTH_SUCCESS)
                .eventLevel(GatewayEvent.EventLevel.INFO)
                .source("gateway")
                .message("认证成功")
                .clientIp(clientIp)
                .path(path)
                .method(method)
                .user(user)
                .httpStatus(200)
                .metadata(metadata)
                .build();
        publishEvent(event);
    }

    public void publishServerError(String clientIp, String path, String method, String errorDetail, Map<String, Object> metadata) {
        GatewayEvent event = GatewayEvent.builder()
                .eventType(GatewayEvent.EventType.SERVER_ERROR)
                .eventLevel(GatewayEvent.EventLevel.ERROR)
                .source("gateway")
                .message("网关内部错误")
                .clientIp(clientIp)
                .path(path)
                .method(method)
                .httpStatus(500)
                .metadata(metadata != null ? metadata : Map.of("error", errorDetail))
                .build();
        publishEvent(event);
    }

    @Async("notificationExecutor")
    public void publishEventAsync(GatewayEvent event) {
        publishEvent(event);
    }
}
