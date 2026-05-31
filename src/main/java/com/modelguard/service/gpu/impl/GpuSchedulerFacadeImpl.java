package com.modelguard.service.gpu.impl;

import com.modelguard.common.PageResult;
import com.modelguard.dto.request.GpuNodeRegisterRequest;
import com.modelguard.dto.request.GpuTaskSubmitRequest;
import com.modelguard.dto.request.HeartbeatRequest;
import com.modelguard.dto.response.ClusterStatusResponse;
import com.modelguard.dto.response.GpuNodeResponse;
import com.modelguard.dto.response.GpuTaskResponse;
import com.modelguard.service.gpu.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GpuSchedulerFacadeImpl implements GpuSchedulerFacade {

    private final GpuNodeService gpuNodeService;
    private final GpuTaskService gpuTaskService;
    private final GpuSchedulerService gpuSchedulerService;
    private final GpuClusterMonitorService gpuClusterMonitorService;

    @Override
    public Mono<GpuNodeResponse> registerNode(GpuNodeRegisterRequest request) {
        return gpuNodeService.registerNode(request);
    }

    @Override
    public Mono<GpuNodeResponse> getNode(String nodeId) {
        return gpuNodeService.getNode(nodeId);
    }

    @Override
    public Mono<GpuNodeResponse> updateNodeHeartbeat(String nodeId, HeartbeatRequest request) {
        return gpuNodeService.updateNodeStatus(nodeId, request);
    }

    @Override
    public Mono<PageResult<GpuNodeResponse>> pageNodes(String status, int pageNum, int pageSize) {
        return gpuNodeService.pageNodes(status, pageNum, pageSize);
    }

    @Override
    public Mono<List<GpuNodeResponse>> listAvailableNodes(Integer requiredGpuCount, Integer requiredMemoryGb) {
        return gpuNodeService.listAvailableNodes(requiredGpuCount, requiredMemoryGb);
    }

    @Override
    public Mono<GpuTaskResponse> submitTask(GpuTaskSubmitRequest request) {
        return gpuTaskService.submitTask(request);
    }

    @Override
    public Mono<GpuTaskResponse> getTask(String taskId) {
        return gpuTaskService.getTask(taskId);
    }

    @Override
    public Mono<PageResult<GpuTaskResponse>> pageTasks(String status, Integer priority, int pageNum, int pageSize) {
        return gpuTaskService.pageTasks(status, priority, pageNum, pageSize);
    }

    @Override
    public Mono<GpuTaskResponse> updateTaskProgress(String taskId, int progress, Map<String, Object> data) {
        return gpuTaskService.updateTaskStatus(taskId, "RUNNING", data);
    }

    @Override
    public Mono<GpuTaskResponse> completeTask(String taskId, Map<String, Object> result) {
        return gpuTaskService.markTaskCompleted(taskId, result);
    }

    @Override
    public Mono<GpuTaskResponse> failTask(String taskId, String errorMessage) {
        return gpuTaskService.markTaskFailed(taskId, errorMessage);
    }

    @Override
    public Mono<Boolean> cancelTask(String taskId) {
        return gpuTaskService.cancelTask(taskId);
    }

    @Override
    public Mono<GpuTaskResponse> scheduleNext() {
        return gpuSchedulerService.scheduleNextTask();
    }

    @Override
    public Mono<List<GpuTaskResponse>> scheduleBatch(int batchSize) {
        return gpuSchedulerService.scheduleBatch(batchSize);
    }

    @Override
    public Mono<ClusterStatusResponse> getClusterStatus() {
        return gpuClusterMonitorService.getClusterStatus();
    }

    @Override
    public Mono<Map<String, Object>> getSchedulerStatus() {
        return gpuSchedulerService.getSchedulerStatus();
    }

    @Override
    public Mono<Boolean> pauseScheduler() {
        return gpuSchedulerService.pauseScheduler();
    }

    @Override
    public Mono<Boolean> resumeScheduler() {
        return gpuSchedulerService.resumeScheduler();
    }

    @Override
    public Mono<List<Map<String, Object>>> getAlerts() {
        return gpuClusterMonitorService.getAlerts();
    }

    @Override
    public Mono<Map<String, Object>> getCapacityPlanning() {
        return gpuClusterMonitorService.getCapacityPlanning();
    }
}
