package com.orchestration.scheduler.controller;

import com.orchestration.common.api.ApiConstants;
import com.orchestration.common.base.Result;
import com.orchestration.scheduler.dto.TaskInstanceVO;
import com.orchestration.scheduler.dto.TaskSubmitRequest;
import com.orchestration.scheduler.service.TaskSchedulerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping(ApiConstants.API_V1_PREFIX + "/scheduler")
@RequiredArgsConstructor
public class TaskSchedulerController {

    private final TaskSchedulerService taskSchedulerService;

    @PostMapping("/tasks")
    public Result<String> submitTask(@Valid @RequestBody TaskSubmitRequest request) {
        String taskId = taskSchedulerService.submitTask(request);
        return Result.success("任务提交成功", taskId);
    }

    @GetMapping("/instances/{instanceNo}")
    public Result<TaskInstanceVO> getInstanceStatus(@PathVariable String instanceNo) {
        return Result.success(taskSchedulerService.getInstanceStatus(instanceNo));
    }

    @GetMapping("/tasks/{taskId}/instances")
    public Result<List<TaskInstanceVO>> getTaskInstances(
            @PathVariable Long taskId,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "10") Integer size) {
        return Result.success(taskSchedulerService.getTaskInstances(taskId, page, size));
    }

    @PostMapping("/instances/{instanceNo}/cancel")
    public Result<Boolean> cancelTask(@PathVariable String instanceNo) {
        return Result.success(taskSchedulerService.cancelTask(instanceNo));
    }

    @PostMapping("/instances/{instanceNo}/retry")
    public Result<Boolean> retryTask(@PathVariable String instanceNo) {
        return Result.success(taskSchedulerService.retryTask(instanceNo));
    }

    @GetMapping("/tasks/{taskId}/graph")
    public Result<Map<String, Object>> getTaskGraph(@PathVariable Long taskId) {
        return Result.success(taskSchedulerService.getTaskGraph(taskId));
    }
}
