package com.taskflow.notification.service;

import com.taskflow.notification.model.NotificationRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class WeChatChannel implements NotificationChannel {

    @Override
    public String getChannelName() {
        return "wechat";
    }

    @Override
    public boolean send(NotificationRequest request, String content, String subject) throws Exception {
        log.info("Sending WeChat to: {}, content: {}", request.getReceivers(), content);
        return true;
    }
}
