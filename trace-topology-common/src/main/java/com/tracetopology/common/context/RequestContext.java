package com.tracetopology.common.context;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestContext {

    private String traceId;
    private String requestId;
    private String userId;
    private String clientIp;
    private String userAgent;
    private Instant requestTime;

    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    public long getRequestDurationMs() {
        return requestTime != null
                ? System.currentTimeMillis() - requestTime.toEpochMilli()
                : 0;
    }
}
