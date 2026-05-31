package com.tracetopology.core.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Duration;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProcessingConfig {

    @Builder.Default
    private int poolSize = 10;

    @Builder.Default
    private Duration timeout = Duration.ofSeconds(30);

    @Builder.Default
    private int retries = 3;

    private Map<String, Object> rules;

    public static ProcessingConfig fromMap(Map<String, Object> params) {
        ProcessingConfigBuilder builder = ProcessingConfig.builder();
        if (params != null) {
            if (params.containsKey("poolSize")) {
                builder.poolSize(((Number) params.get("poolSize")).intValue());
            }
            if (params.containsKey("timeoutSeconds")) {
                builder.timeout(Duration.ofSeconds(((Number) params.get("timeoutSeconds")).longValue()));
            }
            if (params.containsKey("retries")) {
                builder.retries(((Number) params.get("retries")).intValue());
            }
            if (params.containsKey("rules")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rules = (Map<String, Object>) params.get("rules");
                builder.rules(rules);
            }
        }
        return builder.build();
    }
}
