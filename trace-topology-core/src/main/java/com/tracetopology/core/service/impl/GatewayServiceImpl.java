package com.tracetopology.core.service.impl;

import com.tracetopology.api.service.GatewayService;
import com.tracetopology.common.exception.BaseException;
import com.tracetopology.core.validation.ParamValidator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@RequiredArgsConstructor
public class GatewayServiceImpl implements GatewayService {

    private final Map<String, Map<String, Object>> routes = new ConcurrentHashMap<>();
    private final Map<String, List<RequestLog>> requestLogs = new ConcurrentHashMap<>();

    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong totalErrors = new AtomicLong(0);
    private final AtomicLong totalLatency = new AtomicLong(0);
    private final Map<String, AtomicLong> requestCountsByPath = new ConcurrentHashMap<>();

    @Override
    public Map<String, Object> routeRequest(String traceId, String path, String method,
                                             Map<String, String> headers, Map<String, Object> body) {
        ParamValidator.validateNotBlank(traceId, "traceId");
        ParamValidator.validateNotBlank(path, "path");
        ParamValidator.validateNotBlank(method, "method");

        totalRequests.incrementAndGet();
        requestCountsByPath.computeIfAbsent(path, k -> new AtomicLong(0)).incrementAndGet();

        log.info("网关路由请求: traceId={}, method={}, path={}", traceId, method, path);

        Map<String, Object> routeConfig = findRoute(path);
        if (routeConfig == null) {
            totalErrors.incrementAndGet();
            throw new BaseException("ROUTE_NOT_FOUND", "未找到路由: " + path);
        }

        String targetService = (String) routeConfig.get("targetService");
        String targetPath = (String) routeConfig.getOrDefault("targetPath", path);

        Map<String, Object> response = new HashMap<>();
        response.put("traceId", traceId);
        response.put("path", path);
        response.put("method", method);
        response.put("targetService", targetService);
        response.put("targetPath", targetPath);
        response.put("routedAt", Instant.now());
        response.put("status", "routed");

        return response;
    }

    @Override
    public void logRequest(String traceId, String path, String method, int statusCode,
                            Duration duration, Map<String, String> headers) {
        ParamValidator.validateNotBlank(traceId, "traceId");
        ParamValidator.validateNotBlank(path, "path");

        RequestLog requestLog = new RequestLog(
                traceId, path, method, statusCode,
                duration.toMillis(), System.currentTimeMillis(),
                headers != null ? new HashMap<>(headers) : new HashMap<>()
        );

        requestLogs.computeIfAbsent(traceId, k -> Collections.synchronizedList(new ArrayList<>()))
                .add(requestLog);

        totalLatency.addAndGet(duration.toMillis());

        if (statusCode >= 400) {
            totalErrors.incrementAndGet();
        }

        log.debug("请求日志已记录: traceId={}, path={}, status={}, duration={}ms",
                traceId, path, statusCode, duration.toMillis());
    }

    @Override
    public List<Map<String, Object>> getRequestLogs(String traceId) {
        ParamValidator.validateNotBlank(traceId, "traceId");

        List<RequestLog> logs = requestLogs.getOrDefault(traceId, Collections.emptyList());
        List<Map<String, Object>> result = new ArrayList<>();

        synchronized (logs) {
            for (RequestLog log : logs) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("traceId", log.getTraceId());
                entry.put("path", log.getPath());
                entry.put("method", log.getMethod());
                entry.put("statusCode", log.getStatusCode());
                entry.put("durationMs", log.getDurationMs());
                entry.put("timestamp", log.getTimestamp());
                entry.put("headers", log.getHeaders());
                result.add(entry);
            }
        }

        return result;
    }

    @Override
    public Map<String, Object> getGatewayStats() {
        Map<String, Object> stats = new HashMap<>();
        long total = totalRequests.get();
        long errors = totalErrors.get();
        long latency = totalLatency.get();

        stats.put("totalRequests", total);
        stats.put("totalErrors", errors);
        stats.put("errorRate", total > 0 ? (double) errors / total : 0);
        stats.put("avgLatencyMs", total > 0 ? (double) latency / total : 0);
        stats.put("activeRoutes", routes.size());
        stats.put("activeTraces", requestLogs.size());

        Map<String, Long> pathStats = new HashMap<>();
        for (Map.Entry<String, AtomicLong> entry : requestCountsByPath.entrySet()) {
            pathStats.put(entry.getKey(), entry.getValue().get());
        }
        stats.put("requestCountsByPath", pathStats);

        return stats;
    }

    @Override
    public void addRoute(String path, Map<String, Object> config) {
        ParamValidator.validateNotBlank(path, "path");
        ParamValidator.validateNotNull(config, "config");
        ParamValidator.validateNotBlank((String) config.get("targetService"), "config.targetService");

        routes.put(path, new HashMap<>(config));
        log.info("路由已添加: path={}, config={}", path, config);
    }

    @Override
    public void removeRoute(String path) {
        ParamValidator.validateNotBlank(path, "path");
        routes.remove(path);
        log.info("路由已移除: path={}", path);
    }

    private Map<String, Object> findRoute(String path) {
        if (routes.containsKey(path)) {
            return routes.get(path);
        }

        for (Map.Entry<String, Map<String, Object>> entry : routes.entrySet()) {
            String routePath = entry.getKey();
            if (pathMatch(routePath, path)) {
                return entry.getValue();
            }
        }

        return null;
    }

    private boolean pathMatch(String routePath, String requestPath) {
        if (routePath.endsWith("/**")) {
            String prefix = routePath.substring(0, routePath.length() - 3);
            return requestPath.startsWith(prefix);
        }
        if (routePath.endsWith("/*")) {
            String prefix = routePath.substring(0, routePath.length() - 2);
            return requestPath.startsWith(prefix) &&
                    requestPath.indexOf('/', prefix.length()) == -1;
        }
        return routePath.equals(requestPath);
    }

    @lombok.Data
    @lombok.AllArgsConstructor
    static class RequestLog {
        private final String traceId;
        private final String path;
        private final String method;
        private final int statusCode;
        private final long durationMs;
        private final long timestamp;
        private final Map<String, String> headers;
    }
}
