package com.streamsql.event;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class EventBus {

    private final EventBusConfig config;

    private final BlockingQueue<DomainEvent<?>> eventQueue = new LinkedBlockingQueue<>();
    private final Map<String, List<EventListener<?>> listeners = new ConcurrentHashMap<>();
    private final List<DomainEvent<?>> deadLetterQueue = new CopyOnWriteArrayList<>();

    private ExecutorService executorService;
    private volatile boolean running = false;

    @PostConstruct
    public void start() {
        running = true;
        executorService = Executors.newFixedThreadPool(config.getConsumerThreads());

        for (int i = 0; i < config.getConsumerThreads(); i++) {
            executorService.submit(this::consumeEvents);
        }

        log.info("EventBus started with {} consumer threads", config.getConsumerThreads());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        log.info("EventBus stopped. DLQ size: {}", deadLetterQueue.size());
    }

    @SuppressWarnings("unchecked")
    public <T> void publish(DomainEvent<T> event) {
        if (eventQueue.size() >= config.getQueueCapacity()) {
            log.warn("Event queue is full, dropping event: {}", event.getEventType());
            if (config.isEnableDeadLetterQueue()) {
                deadLetterQueue.add(event);
            }
            return;
        }

        try {
            eventQueue.offer(event, 100, TimeUnit.MILLISECONDS);
            log.debug("Event published: {} - {}", event.getEventType(), event.getEventId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Failed to publish event", e);
        }
    }

    public <T> void publish(String eventType, T payload) {
        publish(new DomainEvent<>(eventType, payload));
    }

    public <T> void publish(String eventType, String source, T payload) {
        publish(new DomainEvent<>(eventType, source, payload));
    }

    public <T> void subscribe(String eventType, EventListener<T> listener) {
        listeners.computeIfAbsent(eventType, k -> new CopyOnWriteArrayList<>()).add(listener);
        log.debug("Subscribed listener to event: {}", eventType);
    }

    public <T> void unsubscribe(String eventType, EventListener<T> listener) {
        List<EventListener<?>> eventListeners = listeners.get(eventType);
        if (eventListeners != null) {
            eventListeners.remove(listener);
            log.debug("Unsubscribed listener from event: {}", eventType);
        }
    }

    private void consumeEvents() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                DomainEvent<?> event = eventQueue.poll(1, TimeUnit.SECONDS);
                if (event != null) {
                    dispatchEvent(event);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error consuming event", e);
            }
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatchEvent(DomainEvent<?> event) {
        List<EventListener<?>> eventListeners = listeners.getOrDefault(event.getEventType(), List.of());

        List<EventListener<?>> sortedListeners = eventListeners.stream()
                .sorted((a, b) -> Integer.compare(a.getOrder(), b.getOrder()))
                .collect(Collectors.toList());

        for (EventListener listener : sortedListeners) {
            try {
                if (listener.supports(event.getEventType())) {
                    listener.onEvent(event);
                }
            } catch (Exception e) {
                log.error("Error processing event: {} - {}", event.getEventType(), event.getEventId(), e);
                handleFailedEvent(event, listener, e);
            }
        }
    }

    @SuppressWarnings("rawtypes")
    private void handleFailedEvent(DomainEvent<?> event, EventListener listener, Exception e) {
        if (event.getRetryCount() < config.getMaxRetryAttempts()) {
            event.incrementRetryCount();
            log.info("Retrying event {} (attempt {}/{})", event.getEventId(), event.getRetryCount(), config.getMaxRetryAttempts());

            try {
                Thread.sleep(config.getRetryIntervalMs() * event.getRetryCount());
                listener.onEvent(event);
            } catch (Exception retryException) {
                log.error("Retry failed for event: {}", event.getEventId(), retryException);
                if (event.getRetryCount() >= config.getMaxRetryAttempts()) {
                    sendToDeadLetterQueue(event);
                }
            }
        } else {
            sendToDeadLetterQueue(event);
        }
    }

    private void sendToDeadLetterQueue(DomainEvent<?> event) {
        if (config.isEnableDeadLetterQueue()) {
            deadLetterQueue.add(event);
            log.warn("Event sent to DLQ: {} - {}", event.getEventType(), event.getEventId());
        }
    }

    public List<DomainEvent<?>> getDeadLetterQueue() {
        return new ArrayList<>(deadLetterQueue);
    }

    public void clearDeadLetterQueue() {
        deadLetterQueue.clear();
        log.info("Dead letter queue cleared");
    }

    public int getQueueSize() {
        return eventQueue.size();
    }

    public Map<String, Object> getStatistics() {
        Map<String, Object> stats = new ConcurrentHashMap<>();
        stats.put("queueSize", eventQueue.size());
        stats.put("deadLetterQueueSize", deadLetterQueue.size());
        stats.put("subscribedEventTypes", listeners.size());
        stats.put("running", running);
        return stats;
    }
}
