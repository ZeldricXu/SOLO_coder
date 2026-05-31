package com.scheduler.notification.channel;

import com.scheduler.persistence.entity.Notification;
import reactor.core.publisher.Mono;

public interface NotificationChannel {
    String getChannelName();
    Mono<Boolean> send(Notification notification);
    boolean supports(String channel);
}
