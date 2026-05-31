package com.streamsql.event;

public interface EventListener<T> {

    void onEvent(DomainEvent<T> event);

    default boolean supports(String eventType) {
        return true;
    }

    default int getOrder() {
        return 0;
    }
}
