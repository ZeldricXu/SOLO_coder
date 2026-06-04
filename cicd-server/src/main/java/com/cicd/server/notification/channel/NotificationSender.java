package com.cicd.server.notification.channel;

import com.cicd.common.enums.NotificationChannel;
import java.util.Map;

public interface NotificationSender {
    NotificationChannel getChannelType();
    boolean send(String target, String title, String content, Map<String, Object> extra) throws Exception;
}
