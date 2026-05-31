package com.solo.config.module.notification;

public interface NotificationChannel {

    String getType();

    int getPriority();

    boolean isEnabled();

    boolean send(String recipient, String title, String content);
}
