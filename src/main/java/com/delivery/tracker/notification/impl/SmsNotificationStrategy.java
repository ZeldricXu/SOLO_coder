package com.delivery.tracker.notification.impl;

import com.delivery.tracker.entity.Notification;
import com.delivery.tracker.notification.NotificationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Random;

/**
 * 短信通知策略
 */
@Slf4j
@Component("SMS")
public class SmsNotificationStrategy implements NotificationStrategy {

    private static final String TYPE = "SMS";
    private final Random random = new Random();

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getDescription() {
        return "短信通知策略，通过短信网关发送短信通知";
    }

    @Override
    public void send(Notification notification) throws Exception {
        log.debug("发送短信通知: to={}, content={}", notification.getRecipient(), notification.getContent());

        if (random.nextDouble() < 0.15) {
            throw new RuntimeException("短信网关超时");
        }

        Thread.sleep(30 + random.nextInt(50));

        log.debug("短信通知发送成功: to={}", notification.getRecipient());
    }

    @Override
    public boolean supports(String type) {
        return TYPE.equalsIgnoreCase(type);
    }

    @Override
    public RetryConfig getRetryConfig() {
        return new RetryConfig(
                5,
                60000,
                1.5,
                600000
        );
    }
}
