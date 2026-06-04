package com.battle.platform.battlefield.event;

import com.google.common.eventbus.EventBus;
import org.springframework.stereotype.Component;

@Component
public class BattlefieldEventBus {

    private final EventBus eventBus = new EventBus("battlefield-event-bus");

    public void register(Object listener) {
        eventBus.register(listener);
    }

    public void unregister(Object listener) {
        eventBus.unregister(listener);
    }

    public void post(Object event) {
        eventBus.post(event);
    }
}
