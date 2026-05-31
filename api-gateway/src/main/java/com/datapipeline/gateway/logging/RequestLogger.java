package com.datapipeline.gateway.logging;

import com.datapipeline.common.tracing.TraceContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class RequestLogger {

    public void logRequest(String method, String path, Map<String, String> headers, String traceId) {
        log.info("[REQUEST] method={} path={} traceId={} timestamp={}",
                method, path, traceId, Instant.now());
        log.debug("[REQUEST] headers={}", maskSensitiveHeaders(headers));
    }

    public void logResponse(String method, String path, HttpStatus status, long durationMs, String traceId) {
        log.info("[RESPONSE] method={} path={} status={} durationMs={} traceId={}",
                method, path, status.value(), durationMs, traceId);
    }

    public void logError(String method, String path, Throwable error, String traceId) {
        log.error("[ERROR] method={} path={} traceId={}", method, path, traceId, error);
    }

    public RequestLogEntry createEntry(String method, String path, Map<String, String> headers) {
        String traceId = headers.getOrDefault("X-Trace-Id", UUID.randomUUID().toString());
        String spanId = UUID.randomUUID().toString().substring(0, 16);
        return RequestLogEntry.builder()
                .method(method)
                .path(path)
                .traceId(traceId)
                .spanId(spanId)
                .requestHeaders(maskSensitiveHeaders(headers))
                .timestamp(Instant.now())
                .build();
    }

    private Map<String, String> maskSensitiveHeaders(Map<String, String> headers) {
        Map<String, String> masked = new HashMap<>();
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey().toLowerCase();
            if (key.contains("authorization") || key.contains("token") || key.contains("secret") || key.contains("password")) {
                masked.put(entry.getKey(), "***");
            } else {
                masked.put(entry.getKey(), entry.getValue());
            }
        }
        return masked;
    }

}
