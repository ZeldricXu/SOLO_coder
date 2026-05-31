package com.streamsql.event;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "streamsql.event")
public class EventBusConfig {

    private int queueCapacity = 10000;
    private int consumerThreads = 4;
    private long retryIntervalMs = 5000;
    private int maxRetryAttempts = 3;
    private boolean enableDeadLetterQueue = true;

    public static class EventType {
        public static final String QUALITY_CHECK_COMPLETED = "quality_check.completed";
        public static final String QUALITY_RULE_CREATED = "quality_rule.created";
        public static final String VECTOR_INDEX_BUILD_COMPLETED = "vector_index.build_completed";
        public static final String METADATA_CRAWLED = "metadata.crawled";
        public static final String CDC_EVENT_CAPTURED = "cdc.event_captured";
        public static final String LINEAGE_PARSED = "lineage.parsed";
        public static final String DATA_ARCHIVED = "data.archived";
        public static final String ALERT_TRIGGERED = "alert.triggered";
    }
}
