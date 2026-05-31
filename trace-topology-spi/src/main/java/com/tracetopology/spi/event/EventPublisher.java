package com.tracetopology.spi.event;

import java.util.Map;
import java.util.function.Consumer;

public interface EventPublisher {

    void publish(String eventType, Map<String, Object> eventData);

    void publish(String eventType, String key, Map<String, Object> eventData);

    void subscribe(String eventType, Consumer<Map<String, Object>> consumer);

    void unsubscribe(String eventType, Consumer<Map<String, Object>> consumer);
}
