package com.datastandard.common.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.Instant;
import java.util.Map;

@Getter
public class ConfigChangedEvent extends ApplicationEvent {

    private final String configKey;
    private final String configType;
    private final Map<String, Object> oldValue;
    private final Map<String, Object> newValue;
    private final Instant changedAt;
    private final String changedBy;
    private final String traceId;

    public ConfigChangedEvent(Object source, String configKey, String configType,
                              Map<String, Object> oldValue, Map<String, Object> newValue,
                              String changedBy, String traceId) {
        super(source);
        this.configKey = configKey;
        this.configType = configType;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.changedAt = Instant.now();
        this.changedBy = changedBy;
        this.traceId = traceId;
    }
}
