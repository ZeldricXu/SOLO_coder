package com.scheduler.api.controller;

import com.scheduler.common.model.ApiResponse;
import com.scheduler.persistence.entity.ScheduledTask;
import com.scheduler.scheduler.service.ScheduleManagerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final ScheduleManagerService scheduleManagerService;

    @PostMapping
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> createResource(
            @RequestBody Map<String, Object> request) {
        return Mono.fromCallable(() -> {
            String type = (String) request.getOrDefault("type", "task");
            Map<String, Object> config = (Map<String, Object>) request.getOrDefault("config", Map.of());
            Map<String, String> labels = (Map<String, String>) request.getOrDefault("labels", Map.of());

            ScheduledTask task = new ScheduledTask();
            task.setName((String) config.getOrDefault("name", "Resource_" + UUID.randomUUID().toString().substring(0, 8)));
            task.setTaskType(type);
            task.setTargetService((String) config.getOrDefault("targetService", "default"));
            task.setTargetMethod((String) config.getOrDefault("targetMethod", "execute"));
            task.setParameters((Map<String, Object>) config.getOrDefault("parameters", Map.of()));
            task.setLabels(labels);
            task.setStatus("ACTIVE");
            task.setNamespace((String) config.getOrDefault("namespace", "default"));

            if (config.containsKey("cronExpression")) {
                task.setCronExpression((String) config.get("cronExpression"));
            } else if (config.containsKey("fixedRate")) {
                task.setFixedRate(((Number) config.get("fixedRate")).longValue());
            } else if (config.containsKey("fixedDelay")) {
                task.setFixedDelay(((Number) config.get("fixedDelay")).longValue());
            }

            ScheduledTask created = scheduleManagerService.createTask(task);

            Map<String, Object> response = Map.of(
                    "id", created.getTaskId(),
                    "status", "provisioning"
            );

            return ResponseEntity.status(HttpStatus.CREATED)
                    .body(ApiResponse.created(response));
        });
    }

    @GetMapping("/{id}/status")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getResourceStatus(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            ScheduledTask task = scheduleManagerService.getTask(id);
            Map<String, Object> status = Map.of(
                    "id", task.getTaskId(),
                    "status", task.getStatus(),
                    "progress", 0.8,
                    "nextExecutionTime", task.getNextExecutionTime() != null ? task.getNextExecutionTime().toString() : null
            );
            return ResponseEntity.ok(ApiResponse.success(status));
        });
    }

    @PostMapping("/batch")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> batchOperation(
            @RequestBody Map<String, Object> request) {
        return Mono.fromCallable(() -> {
            List<Map<String, Object>> operations = (List<Map<String, Object>>) request.get("operations");
            String batchId = "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);

            List<Map<String, Object>> results = operations.stream().map(op -> {
                String action = (String) op.get("action");
                String id = (String) op.get("id");
                try {
                    switch (action.toLowerCase()) {
                        case "stop" -> scheduleManagerService.pauseTask(id);
                        case "start" -> scheduleManagerService.resumeTask(id);
                        case "delete" -> scheduleManagerService.deleteTask(id);
                    }
                    return Map.of("id", id, "action", action, "status", "success");
                } catch (Exception e) {
                    return Map.of("id", id, "action", action, "status", "failed", "error", e.getMessage());
                }
            }).toList();

            Map<String, Object> response = Map.of(
                    "batchId", batchId,
                    "results", results
            );

            return ResponseEntity.ok(ApiResponse.success(response));
        });
    }
}
