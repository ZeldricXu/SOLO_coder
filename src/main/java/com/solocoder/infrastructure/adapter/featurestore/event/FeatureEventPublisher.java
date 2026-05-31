package com.solocoder.infrastructure.adapter.featurestore.event;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
public class FeatureEventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final List<FeatureEventListener> eventListeners;

    public void publishEvent(FeatureEvent event) {
        applicationEventPublisher.publishEvent(event);

        for (FeatureEventListener listener : eventListeners) {
            if (listener.supports(event.getEventType())) {
                try {
                    listener.onEvent(event);
                } catch (Exception e) {
                }
            }
        }
    }

    public void publishFeatureRegistered(String featureName, String description,
                                          java.util.Map<String, Object> schema) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("description", description);
        payload.put("schema", schema);
        publishEvent(FeatureEvent.create(
                FeatureEvent.EventType.FEATURE_REGISTERED, featureName, null, payload));
    }

    public void publishFeatureIngested(String entityId, String featureName, Object value) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("value", value);
        publishEvent(FeatureEvent.create(
                FeatureEvent.EventType.FEATURE_INGESTED, featureName, entityId, payload));
    }

    public void publishFeatureSynced(String featureName) {
        publishEvent(FeatureEvent.create(
                FeatureEvent.EventType.FEATURE_SYNCED, featureName, null, null));
    }

    public void publishConsistencyCheckFailed(String entityId, String featureName) {
        publishEvent(FeatureEvent.create(
                FeatureEvent.EventType.CONSISTENCY_CHECK_FAILED, featureName, entityId, null));
    }
}
