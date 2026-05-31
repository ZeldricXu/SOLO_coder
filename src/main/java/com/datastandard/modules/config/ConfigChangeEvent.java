package com.datastandard.modules.config;

import com.datastandard.modules.config.dto.ConfigHistory;
import com.datastandard.modules.config.dto.ConfigResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.context.ApplicationEvent;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class ConfigChangeEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private String eventId;
    private String configKey;
    private ConfigChangeType changeType;
    private ConfigResponse oldConfig;
    private ConfigResponse newConfig;
    private String source;
    private LocalDateTime timestamp;
    private String operatedBy;
    private String changeReason;
    private List<String> affectedComponents;
    private ConfigHistory history;

    public ConfigChangeEvent(Object source, String configKey, ConfigChangeType changeType,
                             ConfigResponse oldConfig, ConfigResponse newConfig) {
        super(source);
        this.eventId = java.util.UUID.randomUUID().toString();
        this.configKey = configKey;
        this.changeType = changeType;
        this.oldConfig = oldConfig;
        this.newConfig = newConfig;
        this.timestamp = LocalDateTime.now();
    }

    public enum ConfigChangeType {
        CREATED,
        UPDATED,
        DELETED,
        ROLLBACK,
        ENABLED,
        DISABLED,
        REFRESHED
    }
}
