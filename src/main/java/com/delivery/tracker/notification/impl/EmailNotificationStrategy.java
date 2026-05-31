package com.delivery.tracker.notification.impl;

import com.delivery.tracker.entity.Notification;
import com.delivery.tracker.notification.NotificationStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import java.util.Random;

/**
 * 邮件通知策略
 */
@Slf4j
@Component("EMAIL")
public class EmailNotificationStrategy implements NotificationStrategy {

    private static final String TYPE = "EMAIL";
    private final Random random = new Random();

    @Override
    public String getType() {
        return TYPE;
    }

    @Override
    public String getDescription() {
        return "邮件通知策略，通过SMTP服务发送邮件通知";
    }

    @Override
    public void send(Notification notification) throws Exception {
        log.debug("发送邮件通知: to={}, content={}", notification.getRecipient(), notification.getContent());

        if (random.nextDouble() < 0.2) {
            throw new RuntimeException("邮件服务临时不可用");
        }

        Thread.sleep(50 + random.nextInt(100));

        log.debug("邮件通知发送成功: to={}", notification.getRecipient());
    }

    @Override
    public boolean supports(String type) {
        return TYPE.equalsIgnoreCase(type);
    }

    @Override
    public RetryConfig getRetryConfig() {
        return new RetryConfig(
                3,
                5000,
                2.0,
                300000
        );
    }
}
