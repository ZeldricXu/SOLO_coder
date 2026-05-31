package com.observability.config.event;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.Map;

@Getter
public class ConfigChangedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final String namespace;
    private final Map<String, Object> config;
    private final LocalDateTime changedAt;
    private final String source;

    public ConfigChangedEvent(Object source, String namespace, Map<String, Object> config, String configSource) {
        super(source);
        this.namespace = namespace;
        this.config = config;
        this.changedAt = LocalDateTime.now();
        this.source = configSource;
    }
}
