package com.datateam.loganalyzer.notification;

import com.datateam.loganalyzer.model.AlertEvent;
import com.datateam.loganalyzer.model.NotificationConfig;

public interface Notifier {

    boolean send(AlertEvent alert);

    String getName();

    NotificationConfig.ChannelType getType();

    boolean isEnabled();

    NotificationConfig getConfig();

    default boolean isCircuitBreakerOpen() {
        return false;
    }

    default int getFailureCount() {
        return 0;
    }

    default void reset() {
    }
}
