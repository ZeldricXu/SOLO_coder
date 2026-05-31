package com.chain.infrastructure.eventlistener.callback;

import com.chain.infrastructure.eventlistener.dto.EventLog;

@FunctionalInterface
public interface EventCallback {

    void onEvent(EventLog eventLog);

    default String getName() {
        return this.getClass().getSimpleName();
    }
}
