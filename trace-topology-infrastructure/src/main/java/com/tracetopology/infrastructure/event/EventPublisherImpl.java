package com.tracetopology.infrastructure.event;

import com.tracetopology.spi.event.EventPublisher;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
@Service
public class EventPublisherImpl implements EventPublisher {

    private final ApplicationEventPublisher applicationEventPublisher;
    private final Map<String, List<Consumer<Map<String, Object>>>> subscribers = new ConcurrentHashMap<>();

    public EventPublisherImpl(ApplicationEventPublisher applicationEventPublisher) {
        this.applicationEventPublisher = applicationEventPublisher;
    }

    @Override
    public void publish(String eventType, Map<String, Object> eventData) {
        log.debug("发布事件: type={}, data={}", eventType, eventData);
        applicationEventPublisher.publishEvent(new DomainEvent(eventType, eventData));
        notifySubscribers(eventType, eventData);
    }

    @Override
    public void publish(String eventType, String key, Map<String, Object> eventData) {
        log.debug("发布事件: type={}, key={}, data={}", eventType, key, eventData);
        applicationEventPublisher.publishEvent(new DomainEvent(eventType, key, eventData));
        notifySubscribers(eventType, eventData);
    }

    @Override
    public void subscribe(String eventType, Consumer<Map<String, Object>> consumer) {
        log.debug("订阅事件: type={}", eventType);
        subscribers.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>())
                .add(consumer);
    }

    @Override
    public void unsubscribe(String eventType, Consumer<Map<String, Object>> consumer) {
        log.debug("取消订阅: type={}", eventType);
        List<Consumer<Map<String, Object>>> consumers = subscribers.get(eventType);
        if (consumers != null) {
            consumers.remove(consumer);
        }
    }

    private void notifySubscribers(String eventType, Map<String, Object> eventData) {
        List<Consumer<Map<String, Object>>> consumers = subscribers.get(eventType);
        if (consumers != null) {
            for (Consumer<Map<String, Object>> consumer : consumers) {
                try {
                    consumer.accept(eventData);
                } catch (Exception e) {
                    log.warn("事件订阅者处理失败: type={}, error={}", eventType, e.getMessage());
                }
            }
        }

        List<Consumer<Map<String, Object>>> allConsumers = subscribers.get("*");
        if (allConsumers != null) {
            for (Consumer<Map<String, Object>> consumer : allConsumers) {
                try {
                    consumer.accept(eventData);
                } catch (Exception e) {
                    log.warn("事件订阅者处理失败: type={}, error={}", eventType, e.getMessage());
                }
            }
        }
    }

    public static class DomainEvent {
        private final String type;
        private final String key;
        private final Map<String, Object> data;
        private final long timestamp;

        public DomainEvent(String type, Map<String, Object> data) {
            this(type, null, data);
        }

        public DomainEvent(String type, String key, Map<String, Object> data) {
            this.type = type;
            this.key = key;
            this.data = data;
            this.timestamp = System.currentTimeMillis();
        }

        public String getType() { return type; }
        public String getKey() { return key; }
        public Map<String, Object> getData() { return data; }
        public long getTimestamp() { return timestamp; }
    }
}
