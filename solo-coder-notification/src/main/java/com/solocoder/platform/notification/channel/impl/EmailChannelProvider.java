package com.solocoder.platform.notification.channel.impl;

import com.solocoder.platform.notification.channel.ChannelProvider;
import com.solocoder.platform.notification.model.NotificationRequest;
import com.solocoder.platform.notification.model.NotificationResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Slf4j
@Component
public class EmailChannelProvider implements ChannelProvider {

    private static final String CHANNEL_TYPE = "EMAIL";

    @Override
    public String getChannelType() {
        return CHANNEL_TYPE;
    }

    @Override
    public NotificationResult send(NotificationRequest request, String renderedContent) {
        String notificationId = UUID.randomUUID().toString();
        long start = System.currentTimeMillis();
        try {
            log.info("Sending email to {}: subject length={}", request.getRecipient(), renderedContent.length());
            Thread.sleep(50);
            long duration = System.currentTimeMillis() - start;
            log.info("Email sent successfully: id={}, to={}, duration={}ms", notificationId, request.getRecipient(), duration);
            return NotificationResult.builder()
                    .notificationId(notificationId)
                    .channel(CHANNEL_TYPE)
                    .recipient(request.getRecipient())
                    .status(NotificationResult.NotificationStatus.SENT)
                    .renderedContent(renderedContent)
                    .durationMs(duration)
                    .sentAt(LocalDateTime.now())
                    .build();
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - start;
            log.error("Failed to send email: to={}", request.getRecipient(), e);
            return NotificationResult.builder()
                    .notificationId(notificationId)
                    .channel(CHANNEL_TYPE)
                    .recipient(request.getRecipient())
                    .status(NotificationResult.NotificationStatus.FAILED)
                    .durationMs(duration)
                    .sentAt(LocalDateTime.now())
                    .errorMessage(e.getMessage())
                    .build();
        }
    }

    @Override
    public boolean supports(String channelType) {
        return CHANNEL_TYPE.equalsIgnoreCase(channelType);
    }
}
