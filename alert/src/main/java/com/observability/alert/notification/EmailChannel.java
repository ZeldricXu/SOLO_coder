package com.observability.alert.notification;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class EmailChannel implements NotificationChannel {

    @Override
    public String getType() {
        return "email";
    }

    @Override
    public void send(String title, String message, Map<String, Object> config) {
        String to = (String) config.get("to");
        if (to == null) {
            log.warn("Email recipient not configured");
            return;
        }

        log.info("Sending email notification - to: {}, title: {}", to, title);
        log.debug("Email message: {}", message);
    }
}
