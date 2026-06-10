package com.loganalytics.pipeline.filter;

import com.loganalytics.common.model.LogEvent;
import com.loganalytics.common.model.LogLevel;
import com.loganalytics.pipeline.config.PipelineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class LogFilter {
    private static final Logger log = LoggerFactory.getLogger(LogFilter.class);

    private PipelineConfig config;
    private List<String> noiseKeywords;
    private LogLevel minLevel;
    private boolean excludeHealthChecks;
    private final AtomicLong totalFiltered;
    private final AtomicLong totalProcessed;

    public LogFilter() {
        this.noiseKeywords = java.util.Collections.emptyList();
        this.minLevel = null;
        this.excludeHealthChecks = false;
        this.totalFiltered = new AtomicLong(0);
        this.totalProcessed = new AtomicLong(0);
    }

    public void configure(PipelineConfig.FilterConfig filterConfig) {
        this.noiseKeywords = filterConfig.noiseKeywords() != null ? filterConfig.noiseKeywords() : java.util.Collections.emptyList();
        this.minLevel = filterConfig.minLevel() != null ? LogLevel.valueOf(filterConfig.minLevel()) : null;
        this.excludeHealthChecks = filterConfig.excludeHealthChecks();
    }

    public LogFilter(PipelineConfig config) {
        this.config = config;
        this.noiseKeywords = config.getNoiseKeywords();
        this.minLevel = config.getMinLogLevel();
        this.excludeHealthChecks = config.isHealthCheckExcludeEnabled();
        this.totalFiltered = new AtomicLong(0);
        this.totalProcessed = new AtomicLong(0);
    }

    public boolean accept(LogEvent event) {
        totalProcessed.incrementAndGet();

        if (filterByLevel(event)) {
            totalFiltered.incrementAndGet();
            event.addTag("filtered_reason", "level_below_threshold");
            return false;
        }

        if (filterByNoiseKeywords(event)) {
            totalFiltered.incrementAndGet();
            event.addTag("filtered_reason", "noise_keyword");
            return false;
        }

        if (filterByHealthCheck(event)) {
            totalFiltered.incrementAndGet();
            event.addTag("filtered_reason", "health_check");
            return false;
        }

        return true;
    }

    private boolean filterByLevel(LogEvent event) {
        if (minLevel == null || event.getLevel() == null) return false;
        return event.getLevel().ordinal() < minLevel.ordinal();
    }

    private boolean filterByNoiseKeywords(LogEvent event) {
        String message = event.getMessage();
        String rawMessage = event.getRawMessage();
        if (message == null && rawMessage == null) return false;

        String lowerMessage = message != null ? message.toLowerCase() : "";
        String lowerRaw = rawMessage != null ? rawMessage.toLowerCase() : "";

        for (String keyword : noiseKeywords) {
            if (lowerMessage.contains(keyword) || lowerRaw.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private boolean filterByHealthCheck(LogEvent event) {
        if (!excludeHealthChecks) return false;

        String message = event.getMessage();
        if (message == null) return false;

        String lower = message.toLowerCase();
        return lower.contains("healthcheck")
                || lower.contains("/actuator/health")
                || lower.contains("/health")
                || lower.contains("health check")
                || lower.contains("service is up")
                || lower.contains("alive");
    }

    public long getTotalFiltered() {
        return totalFiltered.get();
    }

    public long getTotalProcessed() {
        return totalProcessed.get();
    }

    public double getFilterRate() {
        long total = totalProcessed.get();
        if (total == 0) return 0.0;
        return (double) totalFiltered.get() / total * 100;
    }
}
