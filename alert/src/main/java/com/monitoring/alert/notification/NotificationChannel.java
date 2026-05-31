package com.monitoring.alert.notification;

import com.monitoring.alert.model.AlertEvent;
import reactor.core.publisher.Mono;

public interface NotificationChannel {

    String getName();

    Mono<Void> send(AlertEvent alert);

    default boolean supports(String channelName) {
        return getName().equalsIgnoreCase(channelName);
    }
}
