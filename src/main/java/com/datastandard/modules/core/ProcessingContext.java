package com.datastandard.modules.core;

import com.datastandard.modules.core.dto.StandardizationConfig;
import com.datastandard.modules.core.dto.TransformResponse;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Getter
@Setter
public class ProcessingContext {

    private String requestId;

    private String dataSource;

    private String datasetName;

    private String templateId;

    private StandardizationConfig config;

    private Instant startTime;

    private Instant endTime;

    private AtomicInteger successCount = new AtomicInteger(0);

    private AtomicInteger failedCount = new AtomicInteger(0);

    private int totalRecords;

    private int currentRecordIndex;

    private List<TransformResponse.TransformError> errors = new ArrayList<>();

    private Map<String, Object> metrics = new ConcurrentHashMap<>();

    private Map<String, Object> attributes = new HashMap<>();

    private boolean fallbackMode = false;

    private String fallbackReason;

    public ProcessingContext() {
    }

    public ProcessingContext(String requestId, String dataSource, String datasetName) {
        this.requestId = requestId;
        this.dataSource = dataSource;
        this.datasetName = datasetName;
        this.startTime = Instant.now();
    }

    public void incrementSuccess() {
        successCount.incrementAndGet();
    }

    public void incrementFailed() {
        failedCount.incrementAndGet();
    }

    public void addError(String field, String originalValue, String errorCode, String errorMessage) {
        TransformResponse.TransformError error = TransformResponse.TransformError.builder()
                .recordIndex(currentRecordIndex)
                .field(field)
                .originalValue(originalValue)
                .errorCode(errorCode)
                .errorMessage(errorMessage)
                .build();
        errors.add(error);
    }

    public void addMetric(String key, Object value) {
        metrics.put(key, value);
    }

    public Object getAttribute(String key) {
        return attributes.get(key);
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    public boolean hasErrors() {
        return !errors.isEmpty();
    }

    public long getDurationMs() {
        if (endTime == null) {
            endTime = Instant.now();
        }
        return java.time.Duration.between(startTime, endTime).toMillis();
    }

    public void complete() {
        this.endTime = Instant.now();
    }

    public void enableFallback(String reason) {
        this.fallbackMode = true;
        this.fallbackReason = reason;
    }
}
