package com.metricplatform.controller;

import com.metricplatform.common.ApiResponse;
import com.metricplatform.event.GatewayEvent;
import com.metricplatform.event.GatewayEventHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gateway/events")
@RequiredArgsConstructor
public class GatewayEventController {

    private final GatewayEventHistory eventHistory;

    @GetMapping
    public Mono<ApiResponse<List<GatewayEvent>>> getEvents(
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(required = false) GatewayEvent.EventType type,
            @RequestParam(required = false) GatewayEvent.EventLevel level,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {

        List<GatewayEvent> events;

        if (type != null) {
            events = eventHistory.getEventsByType(type, limit);
        } else if (level != null) {
            events = eventHistory.getEventsByLevel(level, limit);
        } else if (startTime != null && endTime != null) {
            events = eventHistory.getEventsByTimeRange(startTime, endTime, limit);
        } else {
            events = eventHistory.getRecentEvents(limit);
        }

        return Mono.just(ApiResponse.success(events));
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Long>>> getEventStats(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {

        if (startTime == null) {
            startTime = LocalDateTime.now().minusHours(24);
        }
        if (endTime == null) {
            endTime = LocalDateTime.now();
        }

        Map<String, Long> stats = eventHistory.getEventStats(startTime, endTime);
        stats.put("historySize", (long) eventHistory.getHistorySize());

        return Mono.just(ApiResponse.success(stats));
    }

    @GetMapping("/recent")
    public Mono<ApiResponse<List<GatewayEvent>>> getRecentEvents(
            @RequestParam(defaultValue = "50") int limit) {
        List<GatewayEvent> events = eventHistory.getRecentEvents(limit);
        return Mono.just(ApiResponse.success(events));
    }

    @GetMapping("/types")
    public Mono<ApiResponse<GatewayEvent.EventType[]>> getEventTypes() {
        return Mono.just(ApiResponse.success(GatewayEvent.EventType.values()));
    }

    @GetMapping("/levels")
    public Mono<ApiResponse<GatewayEvent.EventLevel[]>> getEventLevels() {
        return Mono.just(ApiResponse.success(GatewayEvent.EventLevel.values()));
    }

    @DeleteMapping
    public Mono<ApiResponse<Map<String, Object>>> clearHistory() {
        eventHistory.clearHistory();
        Map<String, Object> result = Map.of(
                "message", "事件历史已清空",
                "clearedAt", LocalDateTime.now()
        );
        return Mono.just(ApiResponse.success(result));
    }
}
