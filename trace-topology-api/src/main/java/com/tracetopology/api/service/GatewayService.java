package com.tracetopology.api.service;

import java.time.Duration;
import java.util.List;
import java.util.Map;

public interface GatewayService {

    Map<String, Object> routeRequest(String traceId, String path, String method,
                                      Map<String, String> headers, Map<String, Object> body);

    void logRequest(String traceId, String path, String method, int statusCode,
                     Duration duration, Map<String, String> headers);

    List<Map<String, Object>> getRequestLogs(String traceId);

    Map<String, Object> getGatewayStats();

    void addRoute(String path, Map<String, Object> config);

    void removeRoute(String path);
}
