package com.scheduler.api.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.scheduler.common.model.ApiResponse;
import com.scheduler.data.repository.TaskExecutionRepository;
import com.scheduler.persistence.entity.TaskExecution;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;

@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class TaskExecutionController {

    private final TaskExecutionRepository executionRepository;

    @GetMapping("/{runId}")
    public Mono<ResponseEntity<ApiResponse<TaskExecution>>> getExecution(@PathVariable String runId) {
        return Mono.fromCallable(() -> {
            TaskExecution execution = executionRepository.findByRunId(runId);
            return ResponseEntity.ok(ApiResponse.success(execution));
        });
    }

    @GetMapping
    public Mono<ResponseEntity<ApiResponse<List<TaskExecution>>>> listExecutions(
            @RequestParam String taskId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Mono.fromCallable(() -> {
            IPage<TaskExecution> result = executionRepository.findByTaskId(taskId, page, size);
            ApiResponse.Pagination pagination = ApiResponse.Pagination.builder()
                    .total(result.getTotal())
                    .page((int) result.getCurrent())
                    .size((int) result.getSize())
                    .totalPages((int) result.getPages())
                    .build();
            return ResponseEntity.ok(ApiResponse.success(result.getRecords(), pagination));
        });
    }
}
