package com.metricplatform.service;

import com.metricplatform.entity.SysNotificationRecord;

public interface NotificationChannel {

    String getChannelName();

    boolean send(SysNotificationRecord record);

    default boolean supports(String channel) {
        return getChannelName().equalsIgnoreCase(channel);
    }
}
