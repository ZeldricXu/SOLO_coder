package com.monitoring.alert.notification.impl;

import com.monitoring.alert.model.AlertEvent;
import com.monitoring.alert.notification.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

@Slf4j
@Component
public class ConsoleNotificationChannel implements NotificationChannel {

    @Override
    public String getName() {
        return "console";
    }

    @Override
    public Mono<Void> send(AlertEvent alert) {
        return Mono.fromRunnable(() -> {
            log.info("ALERT [{}] {}: {} - {}",
                    alert.getSeverity().toUpperCase(),
                    alert.getAlertId(),
                    alert.getMessage(),
                    alert.getLabels());
        });
    }
}
