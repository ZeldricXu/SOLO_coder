package com.delivery.tracker.notification.impl;

import com.delivery.tracker.entity.Notification;
import com.delivery.tracker.notification.NotificationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Random;

/**
 * Webhook通知策略
 */
@Slf4j
@Component("WEBHOOK")
public class WebhookNotificationStrategy implements NotificationStrategy {

    private static final String TYPE = "WEBHOOK";
    private final Random random = new Random();

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getDescription() {
        return "Webhook通知策略，通过HTTP回调发送通知";
    }

    @Override
    public void send(Notification notification) throws Exception {
        log.debug("发送Webhook通知: url={}, content={}", notification.getRecipient(), notification.getContent());

        if (random.nextDouble() < 0.25) {
            throw new RuntimeException("Webhook端点返回非200状态码");
        }

        Thread.sleep(100 + random.nextInt(200));

        log.debug("Webhook通知发送成功: url={}", notification.getRecipient());
    }

    @Override
    public boolean supports(String type) {
        return TYPE.equalsIgnoreCase(type);
    }

    @Override
    public RetryConfig getRetryConfig() {
        return new RetryConfig(
                3,
                10000,
                2.0,
                60000
        );
    }
}
