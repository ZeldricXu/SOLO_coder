package com.streamsql.event;

import com.streamsql.common.ApiResponse;
import com.streamsql.feature.FeatureFlagService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventBusController {

    private final EventBus eventBus;
    private final EventBusConfig eventBusConfig;
    private final FeatureFlagService featureFlagService;

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getStats() {
        return featureFlagService.executeWithFeature(
                "event-driven-architecture",
                () -> ApiResponse.success(eventBus.getStatistics()),
                () -> ApiResponse.error(400, "Event driven architecture feature is disabled")
        );
    }

    @GetMapping("/config")
    public Mono<ApiResponse<EventBusConfig>> getConfig() {
        return Mono.just(ApiResponse.success(eventBusConfig));
    }

    @GetMapping("/dlq")
    public Mono<ApiResponse<List<DomainEvent<?>>>> getDeadLetterQueue() {
        return featureFlagService.executeWithFeature(
                "event-driven-architecture",
                () -> ApiResponse.success(eventBus.getDeadLetterQueue()),
                () -> ApiResponse.error(400, "Event driven architecture feature is disabled")
        );
    }

    @DeleteMapping("/dlq")
    public Mono<ApiResponse<Void>> clearDeadLetterQueue() {
        eventBus.clearDeadLetterQueue();
        return Mono.just(ApiResponse.success(null));
    }

    @PostMapping("/publish")
    public Mono<ApiResponse<String>> publishEvent(@RequestBody Map<String, Object> request) {
        return featureFlagService.executeWithFeature(
                "event-driven-architecture",
                () -> {
                    String eventType = (String) request.get("eventType");
                    Object payload = request.get("payload");
                    String source = (String) request.getOrDefault("source", "api");

                    eventBus.publish(eventType, source, payload);

                    return ApiResponse.success("Event published");
                },
                () -> ApiResponse.error(400, "Event driven architecture feature is disabled")
        );
    }

    @GetMapping("/types")
    public Mono<ApiResponse<Map<String, String>>> getEventTypes() {
        Map<String, String> types = new HashMap<>();
        types.put("QUALITY_CHECK_COMPLETED", EventBusConfig.EventType.QUALITY_CHECK_COMPLETED);
        types.put("QUALITY_RULE_CREATED", EventBusConfig.EventType.QUALITY_RULE_CREATED);
        types.put("VECTOR_INDEX_BUILD_COMPLETED", EventBusConfig.EventType.VECTOR_INDEX_BUILD_COMPLETED);
        types.put("METADATA_CRAWLED", EventBusConfig.EventType.METADATA_CRAWLED);
        types.put("CDC_EVENT_CAPTURED", EventBusConfig.EventType.CDC_EVENT_CAPTURED);
        types.put("LINEAGE_PARSED", EventBusConfig.EventType.LINEAGE_PARSED);
        types.put("DATA_ARCHIVED", EventBusConfig.EventType.DATA_ARCHIVED);
        types.put("ALERT_TRIGGERED", EventBusConfig.EventType.ALERT_TRIGGERED);
        return Mono.just(ApiResponse.success(types));
    }
}
