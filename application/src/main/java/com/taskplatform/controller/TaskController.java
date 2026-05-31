package com.taskplatform.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.taskplatform.common.dto.OperationRequest;
import com.taskplatform.common.dto.OperationResult;
import com.taskplatform.common.enums.TaskStatus;
import com.taskplatform.common.response.ApiResponse;
import com.taskplatform.common.response.PageResult;
import com.taskplatform.common.util.IdGenerator;
import com.taskplatform.core.TaskExecutorService;
import com.taskplatform.persistence.entity.Task;
import com.taskplatform.persistence.mapper.TaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/tasks")
@RequiredArgsConstructor
public class TaskController {

    private final TaskMapper taskMapper;
    private final TaskExecutorService taskExecutorService;

    @PostMapping
    public ApiResponse<Task> createTask(@RequestBody Map<String, Object> request) {
        Task task = new Task();
        task.setTaskId(IdGenerator.generateTaskId());
        task.setType((String) request.getOrDefault("type", "default"));
        task.setStatus(TaskStatus.QUEUED);
        task.setName((String) request.get("name"));
        task.setDescription((String) request.get("description"));
        task.setNamespace((String) request.getOrDefault("namespace", "default"));
        task.setPayload((String) request.get("payload"));
        task.setTimeoutSeconds((Integer) request.getOrDefault("timeoutSeconds", 300));
        task.setMaxRetries((Integer) request.getOrDefault("maxRetries", 3));
        task.setCreatedBy((String) request.getOrDefault("createdBy", "system"));
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        taskMapper.insert(task);
        return ApiResponse.created(task);
    }

    @GetMapping("/{taskId}")
    public ApiResponse<Task> getTask(@PathVariable String taskId) {
        Task task = taskMapper.selectOne(
                new LambdaQueryWrapper<Task>().eq(Task::getTaskId, taskId)
        );
        if (task == null) {
            return ApiResponse.error(404, "Task not found");
        }
        return ApiResponse.success(task);
    }

    @GetMapping
    public ApiResponse<PageResult<Task>> listTasks(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String namespace) {

        LambdaQueryWrapper<Task> query = new LambdaQueryWrapper<>();
        if (status != null) {
            query.eq(Task::getStatus, TaskStatus.valueOf(status.toUpperCase()));
        }
        if (type != null) {
            query.eq(Task::getType, type);
        }
        if (namespace != null) {
            query.eq(Task::getNamespace, namespace);
        }
        query.orderByDesc(Task::getCreatedAt);

        Page<Task> result = taskMapper.selectPage(Page.of(page, pageSize), query);
        return ApiResponse.success(PageResult.of(
                result.getRecords(), result.getTotal(), page, pageSize
        ));
    }

    @PostMapping("/{taskId}/execute")
    public ApiResponse<Object> executeTask(@PathVariable String taskId) {
        Object result = taskExecutorService.executeTask(taskId);
        return ApiResponse.success(result);
    }

    @GetMapping("/{taskId}/status")
    public ApiResponse<Map<String, Object>> getTaskStatus(@PathVariable String taskId) {
        Task task = taskMapper.selectOne(
                new LambdaQueryWrapper<Task>().eq(Task::getTaskId, taskId)
        );
        if (task == null) {
            return ApiResponse.error(404, "Task not found");
        }

        return ApiResponse.success(Map.of(
                "id", task.getTaskId(),
                "status", task.getStatus(),
                "progress", 1.0
        ));
    }

    @PostMapping("/batch")
    public ApiResponse<OperationResult> batchOperation(@RequestBody OperationRequest request) {
        OperationResult result = new OperationResult();
        result.setBatchId(IdGenerator.generateBatchId());
        List<OperationResult.ItemResult> itemResults = new ArrayList<>();

        for (OperationRequest.Operation op : request.getOperations()) {
            OperationResult.ItemResult itemResult = new OperationResult.ItemResult();
            itemResult.setId(op.getId());
            itemResult.setAction(op.getAction());

            try {
                Task task = taskMapper.selectOne(
                        new LambdaQueryWrapper<Task>().eq(Task::getTaskId, op.getId())
                );
                if (task == null) {
                    itemResult.setSuccess(false);
                    itemResult.setMessage("Task not found");
                } else {
                    switch (op.getAction().toLowerCase()) {
                        case "stop":
                        case "cancel":
                            task.setStatus(TaskStatus.CANCELLED);
                            taskMapper.updateById(task);
                            itemResult.setSuccess(true);
                            itemResult.setMessage("Task cancelled");
                            break;
                        case "retry":
                            task.setStatus(TaskStatus.QUEUED);
                            task.setRetryCount(0);
                            taskMapper.updateById(task);
                            itemResult.setSuccess(true);
                            itemResult.setMessage("Task queued for retry");
                            break;
                        default:
                            itemResult.setSuccess(false);
                            itemResult.setMessage("Unknown action: " + op.getAction());
                    }
                }
            } catch (Exception e) {
                itemResult.setSuccess(false);
                itemResult.setMessage(e.getMessage());
            }
            itemResults.add(itemResult);
        }

        result.setResults(itemResults);
        return ApiResponse.success(result);
    }
}
