package com.observability.logpipe.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Data
public class LogEntry implements Serializable {

    private static final long serialVersionUID = 1L;

    private String id;
    private LocalDateTime timestamp;
    private String level;
    private String message;
    private String service;
    private String host;
    private String traceId;
    private Map<String, String> tags;
    private Map<String, Object> fields;
    private String rawLog;
    private String source;
}
