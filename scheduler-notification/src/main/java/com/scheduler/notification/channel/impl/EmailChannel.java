package com.scheduler.notification.channel.impl;

import com.scheduler.notification.channel.NotificationChannel;
import com.scheduler.persistence.entity.Notification;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@Component
@RequiredArgsConstructor
public class EmailChannel implements NotificationChannel {

    private final JavaMailSender mailSender;

    @Override
    public String getChannelName() {
        return "EMAIL";
    }

    @Override
    public Mono<Boolean> send(Notification notification) {
        return Mono.fromCallable(() -> {
            try {
                SimpleMailMessage message = new SimpleMailMessage();
                message.setTo(notification.getRecipient());
                message.setSubject(notification.getSubject());
                message.setText(notification.getContent());
                mailSender.send(message);
                log.info("Email sent to: {}", notification.getRecipient());
                return true;
            } catch (Exception e) {
                log.error("Failed to send email to: {}", notification.getRecipient(), e);
                return false;
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public boolean supports(String channel) {
        return "EMAIL".equalsIgnoreCase(channel);
    }
}
