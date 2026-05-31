package com.dynamiclog.core.controller;

import com.dynamiclog.common.dto.ApiResponse;
import com.dynamiclog.common.entity.Task;
import com.dynamiclog.core.service.CoreProcessingService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/core")
@RequiredArgsConstructor
public class CoreProcessingController {

    private final CoreProcessingService processingService;

    @PostMapping("/execute")
    public Mono<ApiResponse<Object>> execute(@RequestBody Map<String, Object> request) {
        return processingService.executeHandler(request)
                .map(ApiResponse::success)
                .onErrorResume(e -> Mono.just(
                        ApiResponse.error(e instanceof com.dynamiclog.common.exception.BusinessException ?
                                ((com.dynamiclog.common.exception.BusinessException) e).getCode() : 500, e.getMessage())
                ));
    }

    @PostMapping("/tasks")
    public Mono<ApiResponse<Task>> submitTask(@RequestBody Task task) {
        return processingService.submitTask(task)
                .map(ApiResponse::success);
    }

    @GetMapping("/executions/{traceId}")
    public Mono<ApiResponse<Map<String, Object>>> getExecutionStatus(@PathVariable String traceId) {
        return processingService.getExecutionStatus(traceId)
                .map(ApiResponse::success);
    }

    @DeleteMapping("/executions/{traceId}")
    public Mono<ApiResponse<Void>> cancelExecution(@PathVariable String traceId) {
        return processingService.cancelExecution(traceId)
                .then(Mono.just(ApiResponse.success(null)));
    }

    @GetMapping("/status")
    public Mono<ApiResponse<Map<String, Object>>> getSystemStatus() {
        return processingService.getSystemStatus()
                .map(ApiResponse::success);
    }

    @PostMapping("/batch")
    public Mono<ApiResponse<Map<String, Object>>> batchOperations(@RequestBody Map<String, Object> request) {
        List<Map<String, Object>> operations = (List<Map<String, Object>>) request.get("operations");
        String batchId = java.util.UUID.randomUUID().toString();
        return Mono.just(ApiResponse.success(Map.of(
                "batchId", batchId,
                "operationsSubmitted", operations.size()
        )));
    }
}
