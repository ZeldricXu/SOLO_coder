package com.datapipeline.gateway.logging;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
public class HighPerformanceRequestLogger {

    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    public static final String PARENT_SPAN_ID_HEADER = "X-Parent-Span-Id";

    private final SensitiveHeaderFilter sensitiveFilter;
    private final boolean logDebugInfo;
    private final AtomicBoolean initialized = new AtomicBoolean(false);

    public HighPerformanceRequestLogger() {
        this(new SensitiveHeaderFilter(), true);
    }

    public HighPerformanceRequestLogger(SensitiveHeaderFilter sensitiveFilter, boolean logDebugInfo) {
        this.sensitiveFilter = sensitiveFilter;
        this.logDebugInfo = logDebugInfo;
    }

    public void logRequest(String method, String path, Map<String, String> headers, String traceId) {
        log.info("[REQUEST] method={} path={} traceId={}", method, path, traceId);
        if (logDebugInfo && log.isDebugEnabled()) {
            log.debug("[REQUEST] headers={}", sensitiveFilter.maskHeaders(headers));
        }
    }

    public void logResponse(String method, String path, HttpStatus status, long durationMs, String traceId) {
        log.info("[RESPONSE] method={} path={} status={} durationMs={} traceId={}",
                method, path, status.value(), durationMs, traceId);
    }

    public void logError(String method, String path, Throwable error, String traceId) {
        log.error("[ERROR] method={} path={} traceId={}", method, path, traceId, error);
    }

    public RequestLogEntry createEntry(String method, String path, Map<String, String> headers) {
        String traceId = extractOrGenerateTraceId(headers);
        String spanId = generateSpanId();

        return RequestLogEntry.builder()
                .method(method)
                .path(path)
                .traceId(traceId)
                .spanId(spanId)
                .requestHeaders(sensitiveFilter.maskHeaders(headers))
                .timestamp(Instant.now())
                .build();
    }

    private String extractOrGenerateTraceId(Map<String, String> headers) {
        if (headers == null) {
            return generateTraceId();
        }
        String traceId = headers.get(TRACE_ID_HEADER);
        if (traceId == null || traceId.isEmpty()) {
            traceId = headers.get("x-trace-id");
        }
        return traceId != null && !traceId.isEmpty() ? traceId : generateTraceId();
    }

    private static String generateTraceId() {
        return UUID.randomUUID().toString();
    }

    private static String generateSpanId() {
        long msb = System.nanoTime();
        long lsb = UUID.randomUUID().getLeastSignificantBits();
        return new UUID(msb, lsb).toString().replace("-", "").substring(0, 16);
    }

}
