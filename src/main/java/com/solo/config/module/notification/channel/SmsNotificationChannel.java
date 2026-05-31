package com.solo.config.module.notification.channel;

import com.solo.config.module.notification.NotificationChannel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class SmsNotificationChannel implements NotificationChannel {

    @Override
    public String getType() {
        return "sms";
    }

    @Override
    public int getPriority() {
        return 2;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    @Override
    public boolean send(String recipient, String title, String content) {
        try {
            log.info("Sending SMS notification to: {}, title: {}", recipient, title);
            return true;
        } catch (Exception e) {
            log.error("Failed to send SMS notification", e);
            return false;
        }
    }
}
