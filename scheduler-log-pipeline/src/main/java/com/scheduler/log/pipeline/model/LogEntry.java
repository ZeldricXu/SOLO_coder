package com.scheduler.log.pipeline.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogEntry {
    private String id;
    private Instant timestamp;
    private String level;
    private String logger;
    private String loggerName;
    private String message;
    private String thread;
    private String threadName;
    private String service;
    private String host;
    private String traceId;
    private String spanId;
    private Map<String, String> mdc;
    private Map<String, String> labels;
    private String stackTrace;
    private Map<String, Object> structuredData;
    private long sizeBytes;
    private boolean filtered;
    private String filterReason;
    private List<String> destinations;
}
