package com.datapipeline.core.config;

import com.datapipeline.core.transform.TransformRule;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class ProcessConfig {

    private int poolSize;
    private long acquireTimeoutMs;
    private int timeoutSeconds;
    private int maxRetries;
    private List<TransformRule> transformRules;

    public static final int DEFAULT_POOL_SIZE = 10;
    public static final long DEFAULT_ACQUIRE_TIMEOUT_MS = 5000;
    public static final int DEFAULT_TIMEOUT_SECONDS = 30;
    public static final int DEFAULT_MAX_RETRIES = 3;

    public static ProcessConfig defaults() {
        return ProcessConfig.builder()
                .poolSize(DEFAULT_POOL_SIZE)
                .acquireTimeoutMs(DEFAULT_ACQUIRE_TIMEOUT_MS)
                .timeoutSeconds(DEFAULT_TIMEOUT_SECONDS)
                .maxRetries(DEFAULT_MAX_RETRIES)
                .transformRules(List.of())
                .build();
    }

}
