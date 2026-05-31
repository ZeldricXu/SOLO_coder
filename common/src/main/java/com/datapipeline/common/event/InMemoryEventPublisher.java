package com.datapipeline.common.event;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

@Slf4j
public class InMemoryEventPublisher implements EventPublisher {

    private final List<Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();

    @Override
    public void publish(Event event) {
        log.debug("Publishing event: {} from {}", event.getType(), event.getSource());
        for (Consumer<Event> subscriber : subscribers) {
            try {
                subscriber.accept(event);
            } catch (Exception e) {
                log.error("Event subscriber failed for event: {}", event.getType(), e);
            }
        }
    }

    public void subscribe(Consumer<Event> consumer) {
        this.subscribers.add(consumer);
    }

    public List<Consumer<Event>> getSubscribers() {
        return new ArrayList<>(subscribers);
    }

}
