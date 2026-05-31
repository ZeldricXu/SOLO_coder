package com.modelguard.controller;

import com.modelguard.common.ApiResponse;
import com.modelguard.common.PageResult;
import com.modelguard.dto.request.GpuNodeRegisterRequest;
import com.modelguard.dto.request.GpuTaskSubmitRequest;
import com.modelguard.dto.request.HeartbeatRequest;
import com.modelguard.dto.response.ClusterStatusResponse;
import com.modelguard.dto.response.GpuNodeResponse;
import com.modelguard.dto.response.GpuTaskResponse;
import com.modelguard.service.gpu.GpuSchedulerFacade;
import io.micrometer.core.annotation.Timed;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/gpu-scheduler")
@RequiredArgsConstructor
public class GpuSchedulerController {

    private final GpuSchedulerFacade gpuSchedulerFacade;

    @PostMapping("/nodes")
    @Timed(value = "gpu.node.register", description = "Time taken to register GPU node")
    public Mono<ResponseEntity<ApiResponse<GpuNodeResponse>>> registerNode(
            @Valid @RequestBody GpuNodeRegisterRequest request) {
        return gpuSchedulerFacade.registerNode(request)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/nodes/{nodeId}")
    public Mono<ResponseEntity<ApiResponse<GpuNodeResponse>>> getNode(
            @PathVariable String nodeId) {
        return gpuSchedulerFacade.getNode(nodeId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/nodes/{nodeId}/heartbeat")
    public Mono<ResponseEntity<ApiResponse<GpuNodeResponse>>> updateNodeHeartbeat(
            @PathVariable String nodeId,
            @Valid @RequestBody HeartbeatRequest request) {
        return gpuSchedulerFacade.updateNodeHeartbeat(nodeId, request)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/nodes")
    public Mono<ResponseEntity<ApiResponse<PageResult<GpuNodeResponse>>>> pageNodes(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return gpuSchedulerFacade.pageNodes(status, pageNum, pageSize)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/nodes/available")
    public Mono<ResponseEntity<ApiResponse<List<GpuNodeResponse>>>> listAvailableNodes(
            @RequestParam(required = false) Integer requiredGpuCount,
            @RequestParam(required = false) Integer requiredMemoryGb) {
        return gpuSchedulerFacade.listAvailableNodes(requiredGpuCount, requiredMemoryGb)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/tasks")
    @Timed(value = "gpu.task.submit", description = "Time taken to submit GPU task")
    public Mono<ResponseEntity<ApiResponse<GpuTaskResponse>>> submitTask(
            @Valid @RequestBody GpuTaskSubmitRequest request) {
        return gpuSchedulerFacade.submitTask(request)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<ResponseEntity<ApiResponse<GpuTaskResponse>>> getTask(
            @PathVariable String taskId) {
        return gpuSchedulerFacade.getTask(taskId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/tasks")
    public Mono<ResponseEntity<ApiResponse<PageResult<GpuTaskResponse>>>> pageTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer priority,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return gpuSchedulerFacade.pageTasks(status, priority, pageNum, pageSize)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/tasks/{taskId}/progress")
    public Mono<ResponseEntity<ApiResponse<GpuTaskResponse>>> updateTaskProgress(
            @PathVariable String taskId,
            @RequestBody Map<String, Object> request) {
        int progress = request.get("progress") != null ?
                ((Number) request.get("progress")).intValue() : 0;
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) request.get("data");

        return gpuSchedulerFacade.updateTaskProgress(taskId, progress, data)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/tasks/{taskId}/complete")
    public Mono<ResponseEntity<ApiResponse<GpuTaskResponse>>> completeTask(
            @PathVariable String taskId,
            @RequestBody(required = false) Map<String, Object> result) {
        return gpuSchedulerFacade.completeTask(taskId, result)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/tasks/{taskId}/fail")
    public Mono<ResponseEntity<ApiResponse<GpuTaskResponse>>> failTask(
            @PathVariable String taskId,
            @RequestParam String errorMessage) {
        return gpuSchedulerFacade.failTask(taskId, errorMessage)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Mono<ResponseEntity<ApiResponse<Boolean>>> cancelTask(
            @PathVariable String taskId) {
        return gpuSchedulerFacade.cancelTask(taskId)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/schedule/next")
    @Timed(value = "gpu.schedule.next", description = "Time taken to schedule next task")
    public Mono<ResponseEntity<ApiResponse<GpuTaskResponse>>> scheduleNext() {
        return gpuSchedulerFacade.scheduleNext()
                .map(response -> response != null ?
                        ResponseEntity.ok(ApiResponse.success(response)) :
                        ResponseEntity.ok(ApiResponse.success(null)));
    }

    @PostMapping("/schedule/batch")
    public Mono<ResponseEntity<ApiResponse<List<GpuTaskResponse>>>> scheduleBatch(
            @RequestParam(defaultValue = "10") int batchSize) {
        return gpuSchedulerFacade.scheduleBatch(batchSize)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/cluster/status")
    public Mono<ResponseEntity<ApiResponse<ClusterStatusResponse>>> getClusterStatus() {
        return gpuSchedulerFacade.getClusterStatus()
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/scheduler/status")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getSchedulerStatus() {
        return gpuSchedulerFacade.getSchedulerStatus()
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/scheduler/pause")
    public Mono<ResponseEntity<ApiResponse<Boolean>>> pauseScheduler() {
        return gpuSchedulerFacade.pauseScheduler()
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @PostMapping("/scheduler/resume")
    public Mono<ResponseEntity<ApiResponse<Boolean>>> resumeScheduler() {
        return gpuSchedulerFacade.resumeScheduler()
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/alerts")
    public Mono<ResponseEntity<ApiResponse<List<Map<String, Object>>>> getAlerts() {
        return gpuSchedulerFacade.getAlerts()
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping("/capacity-planning")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getCapacityPlanning() {
        return gpuSchedulerFacade.getCapacityPlanning()
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)));
    }
}
