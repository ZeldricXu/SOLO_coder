package com.taskplatform.controller;

import com.taskplatform.common.enums.TaskPriority;
import com.taskplatform.common.response.ApiResponse;
import com.taskplatform.gpu.GpuSchedulerService;
import com.taskplatform.persistence.entity.GpuResource;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gpu")
@RequiredArgsConstructor
public class GpuController {

    private final GpuSchedulerService gpuSchedulerService;

    @PostMapping("/register")
    public ApiResponse<GpuResource> registerGpu(@RequestBody GpuResource gpu) {
        return ApiResponse.created(gpuSchedulerService.registerGpu(gpu));
    }

    @GetMapping
    public ApiResponse<List<GpuResource>> listGpus(
            @RequestParam(required = false) String nodeName,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(gpuSchedulerService.listGpus(nodeName, status));
    }

    @GetMapping("/{gpuId}")
    public ApiResponse<GpuResource> getGpu(@PathVariable String gpuId) {
        return ApiResponse.success(gpuSchedulerService.getGpu(gpuId));
    }

    @PostMapping("/{gpuId}/heartbeat")
    public ApiResponse<Void> heartbeat(
            @PathVariable String gpuId,
            @RequestBody Map<String, Object> metrics) {
        int usedMemory = ((Number) metrics.getOrDefault("usedMemoryMb", 0)).intValue();
        double utilization = ((Number) metrics.getOrDefault("utilization", 0.0)).doubleValue();
        gpuSchedulerService.heartbeat(gpuId, usedMemory, utilization);
        return ApiResponse.success(null);
    }

    @GetMapping("/scheduler/status")
    public ApiResponse<Map<String, Object>> getSchedulerStatus() {
        return ApiResponse.success(gpuSchedulerService.getSchedulerStatus());
    }

    @PostMapping("/tasks/submit")
    public ApiResponse<String> submitTask(@RequestBody Map<String, Object> request) {
        String taskId = (String) request.get("taskId");
        TaskPriority priority = TaskPriority.valueOf(
                ((String) request.getOrDefault("priority", "NORMAL")).toUpperCase());
        int requiredMemory = ((Number) request.getOrDefault("requiredMemoryMb", 1024)).intValue();
        String modelName = (String) request.get("modelName");

        String submittedId = gpuSchedulerService.submitGpuTask(
                taskId, priority, requiredMemory, modelName, () -> "GPU task completed");
        return ApiResponse.created(submittedId);
    }
}
