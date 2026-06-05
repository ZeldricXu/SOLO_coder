package com.datateam.loganalyzer.notification;

import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.NotificationConfig;

public interface NotificationChannel {
    boolean send(AlertEvent alert);
    String getName();
    NotificationConfig.ChannelType getType();
    boolean isEnabled();
    NotificationConfig getConfig();
}
