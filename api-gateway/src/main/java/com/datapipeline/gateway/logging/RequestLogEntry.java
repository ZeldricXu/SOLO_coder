package com.datapipeline.gateway.logging;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RequestLogEntry {

    private String method;
    private String path;
    private String traceId;
    private String spanId;
    private Map<String, String> requestHeaders;
    private String requestBody;
    private HttpStatus status;
    private Map<String, String> responseHeaders;
    private String responseBody;
    private long durationMs;
    private Throwable error;
    private Instant timestamp;
    private Instant completedAt;

}
