package com.datastandard.modules.gateway;

import cn.hutool.core.util.StrUtil;
import com.datastandard.modules.gateway.dto.AccessLogQuery;
import com.datastandard.modules.gateway.dto.GatewayMetrics;
import com.datastandard.modules.gateway.entity.GatewayAccessLog;
import com.datastandard.modules.gateway.mapper.GatewayAccessLogMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
@RequiredArgsConstructor
public class GatewayAccessLogService {

    private static final int MAX_BATCH_SIZE = 100;
    private static final int FLUSH_INTERVAL_SECONDS = 5;
    private static final int MAX_BODY_LENGTH = 4096;

    private final GatewayAccessLogMapper accessLogMapper;
    private final ObjectMapper objectMapper;

    private final Queue<GatewayAccessLog> logBuffer = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler = new ScheduledThreadPoolExecutor(1);

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong successRequests = new AtomicLong(0);
    private final AtomicLong failedRequests = new AtomicLong(0);
    private final AtomicLong blockedRequests = new AtomicLong(0);
    private final AtomicLong rateLimitedRequests = new AtomicLong(0);
    private final AtomicLong circuitOpenRequests = new AtomicLong(0);

    private final Map<String, AtomicLong> requestsByPath = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requestsByMethod = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> requestsByStatus = new ConcurrentHashMap<>();
    private final LinkedList<Long> responseTimes = new LinkedList<>();

    {
        scheduler.scheduleAtFixedRate(this::flushBuffer, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    public void recordAccess(ServerHttpRequest request, ServerHttpResponse response,
                             String requestId, String traceId, String spanId,
                             long durationMs, String errorMessage,
                             String requestBody, String responseBody) {

        totalRequests.incrementAndGet();

        int statusCode = response.getStatusCode() != null ? response.getStatusCode().value() : 0;
        if (statusCode >= 200 && statusCode < 400) {
            successRequests.incrementAndGet();
        } else {
            failedRequests.incrementAndGet();
        }

        String path = request.getPath().value();
        String method = request.getMethod().name();

        requestsByPath.computeIfAbsent(path, k -> new AtomicLong(0)).incrementAndGet();
        requestsByMethod.computeIfAbsent(method, k -> new AtomicLong(0)).incrementAndGet();
        requestsByStatus.computeIfAbsent(String.valueOf(statusCode), k -> new AtomicLong(0)).incrementAndGet();

        synchronized (responseTimes) {
            responseTimes.add(durationMs);
            if (responseTimes.size() > 10000) {
                responseTimes.removeFirst();
            }
        }

        GatewayAccessLog accessLog = GatewayAccessLog.builder()
                .requestId(requestId)
                .traceId(traceId)
                .spanId(spanId)
                .clientIp(getClientIp(request))
                .userId(getUserId(request))
                .method(method)
                .path(path)
                .queryString(request.getURI().getQuery())
                .requestHeaders(headersToJson(request.getHeaders()))
                .requestBody(truncateBody(requestBody))
                .statusCode(statusCode)
                .responseHeaders(headersToJson(response.getHeaders()))
                .responseBody(truncateBody(responseBody))
                .durationMs(durationMs)
                .errorMessage(truncateBody(errorMessage))
                .userAgent(request.getHeaders().getFirst(HttpHeaders.USER_AGENT))
                .referer(request.getHeaders().getFirst(HttpHeaders.REFERER))
                .requestTime(Instant.now().minusMillis(durationMs))
                .responseTime(Instant.now())
                .createdAt(Instant.now())
                .deleted(0)
                .build();

        logBuffer.offer(accessLog);

        if (logBuffer.size() >= MAX_BATCH_SIZE) {
            scheduler.submit(this::flushBuffer);
        }
    }

    public void recordBlockedRequest(ServerHttpRequest request, String reason) {
        blockedRequests.incrementAndGet();
        if ("RATE_LIMITED".equals(reason)) {
            rateLimitedRequests.incrementAndGet();
        } else if ("CIRCUIT_OPEN".equals(reason)) {
            circuitOpenRequests.incrementAndGet();
        }
    }

    public Mono<List<GatewayAccessLog>> queryLogs(AccessLogQuery query) {
        return Mono.fromCallable(() -> {
            int offset = (query.getPage() - 1) * query.getSize();
            return accessLogMapper.findByQuery(query, offset);
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<Long> countLogs(AccessLogQuery query) {
        return Mono.fromCallable(() -> accessLogMapper.countByQuery(query))
                .subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<GatewayMetrics> getMetrics() {
        return Mono.fromCallable(() -> {
            List<Long> times;
            synchronized (responseTimes) {
                times = new ArrayList<>(responseTimes);
            }

            double avgTime = times.stream().mapToLong(Long::longValue).average().orElse(0);
            double p95 = calculatePercentile(times, 95);
            double p99 = calculatePercentile(times, 99);

            return GatewayMetrics.builder()
                    .instanceId(UUID.randomUUID().toString().substring(0, 8))
                    .timestamp(Instant.now())
                    .totalRequests(totalRequests.get())
                    .successRequests(successRequests.get())
                    .failedRequests(failedRequests.get())
                    .blockedRequests(blockedRequests.get())
                    .rateLimitedRequests(rateLimitedRequests.get())
                    .circuitOpenRequests(circuitOpenRequests.get())
                    .averageResponseTimeMs(avgTime)
                    .p95ResponseTimeMs(p95)
                    .p99ResponseTimeMs(p99)
                    .requestsByPath(new ConcurrentHashMap<>(requestsByPath))
                    .requestsByMethod(new ConcurrentHashMap<>(requestsByMethod))
                    .requestsByStatus(new ConcurrentHashMap<>(requestsByStatus))
                    .build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public void flushBuffer() {
        List<GatewayAccessLog> batch = new ArrayList<>();
        GatewayAccessLog logEntry;
        while ((logEntry = logBuffer.poll()) != null && batch.size() < MAX_BATCH_SIZE) {
            batch.add(logEntry);
        }

        if (!batch.isEmpty()) {
            int successCount = 0;
            List<GatewayAccessLog> failed = new ArrayList<>();
            try {
                for (GatewayAccessLog entry : batch) {
                    try {
                        accessLogMapper.insert(entry);
                        successCount++;
                    } catch (Exception e) {
                        failed.add(entry);
                    }
                }
                if (successCount > 0) {
                    log.debug("Flushed {} access logs to database", successCount);
                }
                if (!failed.isEmpty()) {
                    log.warn("{} access logs failed to flush, re-queueing", failed.size());
                    logBuffer.addAll(failed);
                }
            } catch (Exception e) {
                log.error("Failed to flush access logs", e);
                logBuffer.addAll(batch);
            }
        }
    }

    private String getClientIp(ServerHttpRequest request) {
        String ip = request.getHeaders().getFirst("X-Forwarded-For");
        if (StrUtil.isNotBlank(ip) && !"unknown".equalsIgnoreCase(ip)) {
            int index = ip.indexOf(',');
            if (index != -1) {
                return ip.substring(0, index).trim();
            }
            return ip.trim();
        }
        ip = request.getHeaders().getFirst("X-Real-IP");
        if (StrUtil.isNotBlank(ip) && !"unknown".equalsIgnoreCase(ip)) {
            return ip.trim();
        }
        return request.getRemoteAddress() != null ?
                request.getRemoteAddress().getAddress().getHostAddress() : "unknown";
    }

    private String getUserId(ServerHttpRequest request) {
        String userId = request.getHeaders().getFirst("X-User-Id");
        if (StrUtil.isNotBlank(userId)) {
            return userId;
        }
        return "anonymous";
    }

    private String headersToJson(HttpHeaders headers) {
        try {
            Map<String, String> headerMap = new LinkedHashMap<>();
            headers.forEach((key, values) -> headerMap.put(key, String.join(", ", values)));
            return objectMapper.writeValueAsString(headerMap);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private String truncateBody(String body) {
        if (body == null) {
            return null;
        }
        if (body.getBytes(StandardCharsets.UTF_8).length <= MAX_BODY_LENGTH) {
            return body;
        }
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        return new String(bytes, 0, MAX_BODY_LENGTH, StandardCharsets.UTF_8) + "... [truncated]";
    }

    private double calculatePercentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Long> sorted = new ArrayList<>(values);
        Collections.sort(sorted);
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(index, sorted.size() - 1)));
    }
}
