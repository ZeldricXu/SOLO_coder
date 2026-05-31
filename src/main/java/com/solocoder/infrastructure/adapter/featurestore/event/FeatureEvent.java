package com.solocoder.infrastructure.adapter.featurestore.event;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class FeatureEvent {

    public enum EventType {
        FEATURE_REGISTERED,
        FEATURE_INGESTED,
        FEATURE_SYNCED,
        CONSISTENCY_CHECK_FAILED,
        FEATURE_DELETED
    }

    private String eventId;
    private EventType eventType;
    private String featureName;
    private String entityId;
    private Map<String, Object> payload;
    private Instant timestamp;
    private String source;

    public static FeatureEvent create(EventType type, String featureName, String entityId,
                                       Map<String, Object> payload) {
        return FeatureEvent.builder()
                .eventId(UUID.randomUUID().toString())
                .eventType(type)
                .featureName(featureName)
                .entityId(entityId)
                .payload(payload)
                .timestamp(Instant.now())
                .source("feature-store")
                .build();
    }
}
