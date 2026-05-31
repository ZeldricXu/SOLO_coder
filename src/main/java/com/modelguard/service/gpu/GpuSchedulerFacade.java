package com.modelguard.service.gpu;

import com.modelguard.common.PageResult;
import com.modelguard.dto.request.GpuNodeRegisterRequest;
import com.modelguard.dto.request.GpuTaskSubmitRequest;
import com.modelguard.dto.request.HeartbeatRequest;
import com.modelguard.dto.response.ClusterStatusResponse;
import com.modelguard.dto.response.GpuNodeResponse;
import com.modelguard.dto.response.GpuTaskResponse;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface GpuSchedulerFacade {

    Mono<GpuNodeResponse> registerNode(GpuNodeRegisterRequest request);

    Mono<GpuNodeResponse> getNode(String nodeId);

    Mono<GpuNodeResponse> updateNodeHeartbeat(String nodeId, HeartbeatRequest request);

    Mono<PageResult<GpuNodeResponse>> pageNodes(String status, int pageNum, int pageSize);

    Mono<List<GpuNodeResponse>> listAvailableNodes(Integer requiredGpuCount, Integer requiredMemoryGb);

    Mono<GpuTaskResponse> submitTask(GpuTaskSubmitRequest request);

    Mono<GpuTaskResponse> getTask(String taskId);

    Mono<PageResult<GpuTaskResponse>> pageTasks(String status, Integer priority, int pageNum, int pageSize);

    Mono<GpuTaskResponse> updateTaskProgress(String taskId, int progress, Map<String, Object> data);

    Mono<GpuTaskResponse> completeTask(String taskId, Map<String, Object> result);

    Mono<GpuTaskResponse> failTask(String taskId, String errorMessage);

    Mono<Boolean> cancelTask(String taskId);

    Mono<GpuTaskResponse> scheduleNext();

    Mono<List<GpuTaskResponse>> scheduleBatch(int batchSize);

    Mono<ClusterStatusResponse> getClusterStatus();

    Mono<Map<String, Object>> getSchedulerStatus();

    Mono<Boolean> pauseScheduler();

    Mono<Boolean> resumeScheduler();

    Mono<List<Map<String, Object>>> getAlerts();

    Mono<Map<String, Object>> getCapacityPlanning();
}
