package com.solocoder.platform.gpu.controller;

import com.solocoder.platform.common.model.ApiResponse;
import com.solocoder.platform.gpu.model.GpuResource;
import com.solocoder.platform.gpu.model.GpuTask;
import com.solocoder.platform.gpu.service.GpuTaskSchedulingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/gpu")
@RequiredArgsConstructor
public class GpuTaskController {

    private final GpuTaskSchedulingService schedulingService;

    @PostMapping("/tasks")
    public ApiResponse<GpuTask> submitTask(@Valid @RequestBody GpuTask task) {
        return ApiResponse.success(schedulingService.submitTask(task));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<GpuTask> getTask(@PathVariable String taskId) {
        return schedulingService.getTask(taskId)
                .map(ApiResponse::success)
                .orElse(ApiResponse.error(404, "Task not found: " + taskId));
    }

    @GetMapping("/tasks")
    public ApiResponse<List<GpuTask>> listTasks() {
        return ApiResponse.success(schedulingService.listTasks());
    }

    @DeleteMapping("/tasks/{taskId}")
    public ApiResponse<Void> cancelTask(@PathVariable String taskId) {
        schedulingService.cancelTask(taskId);
        return ApiResponse.success();
    }

    @PostMapping("/gpus")
    public ApiResponse<GpuResource> registerGpu(@Valid @RequestBody GpuResource gpu) {
        return ApiResponse.success(schedulingService.registerGpu(gpu));
    }

    @GetMapping("/gpus")
    public ApiResponse<List<GpuResource>> listGpus() {
        return ApiResponse.success(schedulingService.listGpus());
    }

    @PostMapping("/schedule/trigger")
    public ApiResponse<Void> triggerScheduling() {
        schedulingService.triggerScheduling();
        return ApiResponse.success();
    }
}
