package com.scheduler.notification.channel.impl;

import com.scheduler.notification.channel.NotificationChannel;
import com.scheduler.persistence.entity.Notification;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class SmsChannel implements NotificationChannel {

    @Override
    public String getChannelName() {
        return "SMS";
    }

    @Override
    public Mono<Boolean> send(Notification notification) {
        log.info("Sending SMS to: {}, content: {}", notification.getRecipient(), notification.getContent());
        return Mono.just(true);
    }

    @Override
    public boolean supports(String channel) {
        return "SMS".equalsIgnoreCase(channel);
    }
}
