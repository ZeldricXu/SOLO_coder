package com.scheduler.tracing.sampling.impl;

import com.scheduler.persistence.entity.TraceSpan;
import com.scheduler.tracing.sampling.SamplingStrategy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.Map;

@Slf4j
@Component
public class LatencySampler implements SamplingStrategy {

    @Value("${tracing.sampling.latency-threshold-ms:1000}")
    private long latencyThresholdMs;

    @Override
    public String getName() {
        return "LATENCY";
    }

    @Override
    public boolean shouldSample(TraceSpan span) {
        if (span.getDurationMicros() != null) {
            long durationMs = span.getDurationMicros() / 1000;
            return durationMs > latencyThresholdMs;
        }
        return false;
    }

    @Override
    public void updateConfig(Object config) {
        if (config instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> cfg = (Map<String, Object>) config;
            if (cfg.containsKey("thresholdMs")) {
                this.latencyThresholdMs = ((Number) cfg.get("thresholdMs")).longValue();
                log.info("Updated latency sampling threshold to: {}ms", latencyThresholdMs);
            }
        }
    }
}
