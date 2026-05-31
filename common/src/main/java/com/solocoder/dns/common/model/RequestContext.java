package com.solocoder.dns.common.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class RequestContext implements Serializable {
    private String traceId;
    private String userId;
    private String namespace;
    private Map<String, String> headers;
    private LocalDateTime startTime;
    private Map<String, Object> attributes;

    public long getElapsedMs() {
        return java.time.Duration.between(startTime, LocalDateTime.now()).toMillis();
    }
}
