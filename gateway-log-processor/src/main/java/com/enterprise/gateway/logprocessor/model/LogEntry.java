package com.enterprise.gateway.logprocessor.model;

import lombok.Getter;
import lombok.Setter;
import lombok.Builder;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Getter
@Setter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class LogEntry {

    private LogFormat format;

    private String rawLine;

    private long timestamp;

    private String level;

    private String message;

    private String logger;

    private String thread;

    private String service;

    private String traceId;

    private String statusCode;

    private String method;

    private String path;

    private String duration;

    @Builder.Default
    private Map<String, String> fields = new HashMap<>();

    public Instant getTimestampAsInstant() {
        return Instant.ofEpochMilli(timestamp);
    }

}
