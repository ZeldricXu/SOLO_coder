package com.streamsql.streaming;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "streamsql.streaming")
public class StreamingConfig {

    private int batchSize = 1000;
    private int parallelism = 4;
    private long flushIntervalMs = 1000;
    private int maxRetries = 3;
    private long retryDelayMs = 100;
    private boolean enableBackpressure = true;
    private int queueCapacity = 10000;

    public static class ProcessingMode {
        public static final String BATCH = "batch";
        public static final String STREAMING = "streaming";
        public static final String MICRO_BATCH = "micro_batch";
    }
}
