package com.logmanager.service;

import reactor.core.publisher.Mono;
import java.util.Map;

public interface MonitoringService {
    Mono<Map<String, Object>> getSystemMetrics();
    Mono<Map<String, Object>> getServiceMetrics(String serviceName);
    Mono<Map<String, Object>> getJvmMetrics();
    Mono<Map<String, Object>> getDatabaseMetrics();
    Mono<Map<String, Object>> getCacheMetrics();
    void recordLatency(String operation, long latencyMs);
    void recordError(String operation);
    void recordSuccess(String operation);
    Mono<Map<String, Object>> getHealthStatus();
}
