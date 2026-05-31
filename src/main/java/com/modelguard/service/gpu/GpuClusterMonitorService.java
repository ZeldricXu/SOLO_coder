package com.modelguard.service.gpu;

import com.modelguard.dto.response.ClusterStatusResponse;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface GpuClusterMonitorService {

    Mono<ClusterStatusResponse> getClusterStatus();

    Mono<Map<String, Object>> getClusterUtilization();

    Mono<List<Map<String, Object>>> getNodeUtilizationList();

    Mono<Map<String, Object>> getNodeUtilization(String nodeId);

    Mono<Map<String, Object>> getTaskDistribution();

    Mono<List<Map<String, Object>>> getAlerts();

    Mono<Map<String, Object>> getHistoricalMetrics(int hours);

    Mono<Boolean> checkClusterHealth();

    Mono<Map<String, Object>> getCapacityPlanning();

    Mono<List<String>> getOfflineNodes();
}
