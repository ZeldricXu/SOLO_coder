package com.solocoder.infrastructure.adapter.logging.plugin;

import com.solocoder.infrastructure.adapter.logging.StructuredLogEvent;

public interface LogPlugin {

    default void beforeLog(StructuredLogEvent event) {
    }

    default void afterLog(StructuredLogEvent event) {
    }

    default StructuredLogEvent transform(StructuredLogEvent event) {
        return event;
    }

    default boolean supports(String level) {
        return true;
    }

    default int getOrder() {
        return 0;
    }

    default boolean isEnabled() {
        return true;
    }
}
