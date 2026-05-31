package com.llmgateway.gpu.controller;

import com.llmgateway.common.api.R;
import com.llmgateway.common.api.PageResult;
import com.llmgateway.gpu.entity.GpuNode;
import com.llmgateway.gpu.entity.GpuTask;
import com.llmgateway.gpu.service.GpuSchedulerService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/gpu")
@RequiredArgsConstructor
public class GpuSchedulerController {

    private final GpuSchedulerService schedulerService;

    @PostMapping("/tasks")
    public R<GpuTask> submitTask(@Valid @RequestBody GpuTask task) {
        return R.created(schedulerService.submitTask(task));
    }

    @GetMapping("/tasks/{taskId}")
    public R<GpuTask> getTask(@PathVariable String taskId) {
        return R.success(schedulerService.getTask(taskId));
    }

    @GetMapping("/tasks")
    public R<PageResult<GpuTask>> listTasks(
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") Integer pageNum,
            @RequestParam(defaultValue = "10") Integer pageSize) {
        return R.success(schedulerService.listTasks(status, pageNum, pageSize));
    }

    @PostMapping("/tasks/{taskId}/cancel")
    public R<Void> cancelTask(@PathVariable String taskId) {
        schedulerService.cancelTask(taskId);
        return R.success();
    }

    @PostMapping("/tasks/{taskId}/preempt")
    public R<Boolean> preemptTask(@PathVariable String taskId, @RequestParam int priority) {
        return R.success(schedulerService.preemptTask(taskId, priority));
    }

    @GetMapping("/nodes")
    public R<List<GpuNode>> listNodes(@RequestParam(required = false) String status) {
        return R.success(schedulerService.listNodes(status));
    }

    @PostMapping("/nodes")
    public R<GpuNode> registerNode(@Valid @RequestBody GpuNode node) {
        return R.created(schedulerService.registerNode(node));
    }
}
