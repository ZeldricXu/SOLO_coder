package com.deviceops.controller;

import com.deviceops.dto.ApiResponse;
import com.deviceops.entity.OperationTask;
import com.deviceops.service.task.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    @Autowired
    private TaskService taskService;

    @GetMapping("/{taskId}")
    public ApiResponse<OperationTask> getTask(@PathVariable String taskId) {
        OperationTask task = taskService.getTask(taskId);
        return ApiResponse.success(task);
    }

    @GetMapping
    public ApiResponse<List<OperationTask>> getAllTasks() {
        return ApiResponse.success(taskService.getAllTasks());
    }

    @GetMapping("/device/{deviceId}")
    public ApiResponse<List<OperationTask>> getTasksByDevice(@PathVariable String deviceId) {
        return ApiResponse.success(taskService.getTasksByDevice(deviceId));
    }

    @GetMapping("/operator/{operatorId}")
    public ApiResponse<List<OperationTask>> getTasksByOperator(@PathVariable String operatorId) {
        return ApiResponse.success(taskService.getTasksByOperator(operatorId));
    }

    @GetMapping("/status/{status}")
    public ApiResponse<List<OperationTask>> getTasksByStatus(@PathVariable String status) {
        return ApiResponse.success(taskService.getTasksByStatus(status));
    }

    @GetMapping("/fault/{faultId}")
    public ApiResponse<List<OperationTask>> getTasksByFault(@PathVariable String faultId) {
        return ApiResponse.success(taskService.getTasksByFault(faultId));
    }

    @PostMapping("/{taskId}/execute")
    public ApiResponse<OperationTask> executeTask(@PathVariable String taskId,
                                                   @RequestParam(required = false) String operatorId) {
        OperationTask task = taskService.executeTask(taskId, operatorId);
        return ApiResponse.success(task);
    }

    @PostMapping("/{taskId}/complete")
    public ApiResponse<OperationTask> completeTask(@PathVariable String taskId,
                                                    @RequestParam(required = false) String result) {
        OperationTask task = taskService.completeTask(taskId, result);
        return ApiResponse.success(task);
    }

    @PutMapping("/{taskId}/status")
    public ApiResponse<OperationTask> updateTaskStatus(@PathVariable String taskId,
                                                       @RequestParam String status) {
        OperationTask task = taskService.updateTaskStatus(taskId, status);
        return ApiResponse.success(task);
    }

    @GetMapping("/count")
    public ApiResponse<Map<String, Long>> getTaskCount() {
        Map<String, Long> count = new HashMap<>();
        count.put("total", taskService.count());
        count.put("pending", taskService.countByStatus("pending"));
        count.put("assigned", taskService.countByStatus("assigned"));
        count.put("processing", taskService.countByStatus("processing"));
        count.put("completed", taskService.countByStatus("completed"));
        return ApiResponse.success(count);
    }
}
