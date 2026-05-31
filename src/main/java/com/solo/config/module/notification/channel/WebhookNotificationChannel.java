package com.solo.config.module.notification.channel;

import com.solo.config.module.notification.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WebhookNotificationChannel implements NotificationChannel {

    @Override
    public String getType() {
        return "webhook";
    }

    @Override
    public int getPriority() {
        return 3;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean send(String recipient, String title, String content) {
        try {
            log.info("Sending webhook notification to: {}, title: {}", recipient, title);
            return true;
        } catch (Exception e) {
            log.error("Failed to send webhook notification", e);
            return false;
        }
    }
}
