package com.taskflow.notification.service;

import com.taskflow.notification.model.NotificationRequest;

public interface NotificationChannel {

    String getChannelName();

    boolean send(NotificationRequest request, String content, String subject) throws Exception;

    default boolean validate(NotificationRequest request) {
        return request.getReceivers() != null && !request.getReceivers().isEmpty();
    }
}
