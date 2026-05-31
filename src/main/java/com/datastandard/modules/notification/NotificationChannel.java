package com.datastandard.modules.notification;

import com.datastandard.modules.notification.dto.NotificationRequest;
import com.datastandard.modules.notification.dto.NotificationResult;
import reactor.core.publisher.Mono;

public interface NotificationChannel {

    String getChannelName();

    boolean supports(NotificationRequest request);

    Mono<NotificationResult> send(NotificationRequest request, String recipient);

    default int getPriority() {
        return 100;
    }

    default boolean isAvailable() {
        return true;
    }
}
