package com.logistics.controller;

import com.logistics.dto.ApiResponse;
import com.logistics.dto.AssignTaskRequest;
import com.logistics.dto.AssignTaskResponse;
import com.logistics.dto.UpdateTaskRequest;
import com.logistics.dto.UpdateTaskResponse;
import com.logistics.entity.DeliveryTask;
import com.logistics.service.DeliveryService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final DeliveryService deliveryService;

    @PostMapping("/assign")
    public ApiResponse<AssignTaskResponse> assignTask(@Valid @RequestBody AssignTaskRequest request) {
        AssignTaskResponse response = deliveryService.assignTask(request);
        return ApiResponse.success(response);
    }

    @PostMapping("/update")
    public ApiResponse<UpdateTaskResponse> updateTask(@Valid @RequestBody UpdateTaskRequest request) {
        UpdateTaskResponse response = deliveryService.updateTask(request);
        return ApiResponse.success(response);
    }

    @GetMapping("/{taskId}")
    public ApiResponse<DeliveryTask> getTaskById(@PathVariable String taskId) {
        DeliveryTask task = deliveryService.getTaskById(taskId);
        return ApiResponse.success(task);
    }

    @GetMapping("/logistics/{logisticsId}")
    public ApiResponse<DeliveryTask> getTaskByLogisticsId(@PathVariable String logisticsId) {
        DeliveryTask task = deliveryService.getTaskByLogisticsId(logisticsId);
        return ApiResponse.success(task);
    }

    @GetMapping("/courier/{courierId}")
    public ApiResponse<List<DeliveryTask>> getTasksByCourierId(@PathVariable String courierId) {
        List<DeliveryTask> tasks = deliveryService.getTasksByCourierId(courierId);
        return ApiResponse.success(tasks);
    }

    @GetMapping("/list")
    public ApiResponse<List<DeliveryTask>> getAllTasks() {
        List<DeliveryTask> tasks = deliveryService.getAllTasks();
        return ApiResponse.success(tasks);
    }
}
