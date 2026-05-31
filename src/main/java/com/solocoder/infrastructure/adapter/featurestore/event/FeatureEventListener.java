package com.solocoder.infrastructure.adapter.featurestore.event;

public interface FeatureEventListener {

    void onEvent(FeatureEvent event);

    default boolean supports(FeatureEvent.EventType eventType) {
        return true;
    }
}
