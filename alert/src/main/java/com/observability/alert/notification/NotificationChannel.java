package com.observability.alert.notification;

import java.util.Map;

public interface NotificationChannel {

    String getType();

    void send(String title, String message, Map<String, Object> config);
}
