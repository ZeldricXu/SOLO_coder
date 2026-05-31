package com.modelguard.controller;

import com.modelguard.common.ApiResponse;
import com.modelguard.common.PageResult;
import com.modelguard.dto.GpuNodeDTO;
import com.modelguard.dto.GpuTaskSubmitDTO;
import com.modelguard.dto.HeartbeatDTO;
import com.modelguard.entity.GpuNode;
import com.modelguard.entity.GpuTask;
import com.modelguard.service.GpuSchedulerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gpu")
@RequiredArgsConstructor
public class GpuSchedulerController {

    private final GpuSchedulerService gpuSchedulerService;

    @PostMapping("/nodes")
    public Mono<ApiResponse<GpuNode>> registerNode(@Valid @RequestBody GpuNodeDTO dto) {
        return gpuSchedulerService.registerNode(dto)
                .map(ApiResponse::created);
    }

    @GetMapping("/nodes/{nodeId}")
    public Mono<ApiResponse<GpuNode>> getNode(@PathVariable String nodeId) {
        return gpuSchedulerService.getNode(nodeId)
                .map(ApiResponse::success);
    }

    @GetMapping("/nodes")
    public Mono<ApiResponse<List<GpuNode>>> listNodes(
            @RequestParam(required = false) String status) {
        return gpuSchedulerService.listNodes(status)
                .map(ApiResponse::success);
    }

    @PutMapping("/nodes/{nodeId}/status")
    public Mono<ApiResponse<GpuNode>> updateNodeStatus(
            @PathVariable String nodeId,
            @RequestParam String status) {
        return gpuSchedulerService.updateNodeStatus(nodeId, status)
                .map(ApiResponse::success);
    }

    @PostMapping("/nodes/heartbeat")
    public Mono<ApiResponse<GpuNode>> processHeartbeat(@Valid @RequestBody HeartbeatDTO dto) {
        return gpuSchedulerService.processHeartbeat(dto)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/nodes/{nodeId}")
    public Mono<ApiResponse<Void>> removeNode(@PathVariable String nodeId) {
        return gpuSchedulerService.removeNode(nodeId)
                .then(Mono.just(ApiResponse.success()));
    }

    @PostMapping("/tasks")
    public Mono<ApiResponse<GpuTask>> submitTask(@Valid @RequestBody GpuTaskSubmitDTO dto) {
        return gpuSchedulerService.submitTask(dto)
                .map(ApiResponse::created);
    }

    @GetMapping("/tasks/{taskId}")
    public Mono<ApiResponse<GpuTask>> getTask(@PathVariable String taskId) {
        return gpuSchedulerService.getTask(taskId)
                .map(ApiResponse::success);
    }

    @GetMapping("/tasks")
    public Mono<ApiResponse<PageResult<GpuTask>>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Integer priority,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "20") int pageSize) {
        return gpuSchedulerService.pageTasks(status, priority, pageNum, pageSize)
                .map(ApiResponse::success);
    }

    @PostMapping("/scheduler/run")
    public Mono<ApiResponse<GpuTask>> scheduleTask() {
        return gpuSchedulerService.scheduleTask()
                .map(ApiResponse::success)
                .switchIfEmpty(Mono.just(ApiResponse.success(null)));
    }

    @PostMapping("/tasks/{taskId}/start")
    public Mono<ApiResponse<GpuTask>> startTask(
            @PathVariable String taskId,
            @RequestParam String nodeId,
            @RequestParam String gpuIndices) {
        return gpuSchedulerService.startTask(taskId, nodeId, gpuIndices)
                .map(ApiResponse::success);
    }

    @PostMapping("/tasks/{taskId}/complete")
    public Mono<ApiResponse<GpuTask>> completeTask(@PathVariable String taskId) {
        return gpuSchedulerService.completeTask(taskId)
                .map(ApiResponse::success);
    }

    @PostMapping("/tasks/{taskId}/fail")
    public Mono<ApiResponse<GpuTask>> failTask(
            @PathVariable String taskId,
            @RequestBody Map<String, String> body) {
        String errorDetail = body.get("errorDetail");
        return gpuSchedulerService.failTask(taskId, errorDetail)
                .map(ApiResponse::success);
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public Mono<ApiResponse<GpuTask>> cancelTask(@PathVariable String taskId) {
        return gpuSchedulerService.cancelTask(taskId)
                .map(ApiResponse::success);
    }

    @PostMapping("/tasks/{taskId}/preempt")
    public Mono<ApiResponse<Boolean>> preemptTask(
            @PathVariable String taskId,
            @RequestParam String highPriorityTaskId) {
        return gpuSchedulerService.preemptTask(taskId, highPriorityTaskId)
                .map(ApiResponse::success);
    }

    @GetMapping("/cluster/status")
    public Mono<ApiResponse<Map<String, Object>>> getClusterStatus() {
        return gpuSchedulerService.getClusterStatus()
                .map(ApiResponse::success);
    }

    @GetMapping("/queue/pending")
    public Mono<ApiResponse<List<GpuTask>>> getPendingQueue() {
        return gpuSchedulerService.getPendingQueue()
                .map(ApiResponse::success);
    }

    @GetMapping("/nodes/{nodeId}/tasks")
    public Mono<ApiResponse<List<GpuTask>>> getNodeRunningTasks(@PathVariable String nodeId) {
        return gpuSchedulerService.getNodeRunningTasks(nodeId)
                .map(ApiResponse::success);
    }

    @GetMapping("/tasks/{taskId}/status")
    public Mono<ApiResponse<Map<String, Object>>> getTaskStatus(@PathVariable String taskId) {
        return gpuSchedulerService.getTaskStatus(taskId)
                .map(ApiResponse::success);
    }
}
