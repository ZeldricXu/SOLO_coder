package com.solocoder.application.service;

import com.solocoder.domain.model.ApiResponse;
import com.solocoder.domain.model.RunInstance;
import com.solocoder.domain.port.GpuSchedulerPort;
import com.solocoder.domain.port.StructuredLoggerPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class GpuSchedulerService {

    private final GpuSchedulerPort gpuSchedulerPort;
    private final StructuredLoggerPort logger;

    public Mono<ApiResponse<RunInstance>> submitTask(String taskName, int priority,
                                                      int gpuRequirement,
                                                      Map<String, Object> parameters,
                                                      Runnable task) {
        Map<String, Object> context = Map.of(
                "traceId", UUID.randomUUID().toString(),
                "taskName", taskName,
                "priority", priority,
                "gpuRequirement", gpuRequirement
        );
        logger.info("提交GPU任务", context);

        return gpuSchedulerPort.submitTask(taskName, priority, gpuRequirement, parameters, task)
                .map(ApiResponse::success)
                .onErrorResume(e -> {
                    logger.error("GPU任务提交失败", e, context);
                    return Mono.just(ApiResponse.error(500, e.getMessage()));
                });
    }

    public Mono<ApiResponse<Void>> cancelTask(String taskId) {
        Map<String, Object> context = Map.of("taskId", taskId);
        logger.info("取消GPU任务", context);

        return gpuSchedulerPort.cancelTask(taskId)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> {
                    logger.error("取消GPU任务失败", e, context);
                    return Mono.just(ApiResponse.error(500, e.getMessage()));
                });
    }

    public Mono<ApiResponse<RunInstance>> getTaskStatus(String taskId) {
        return gpuSchedulerPort.getTaskStatus(taskId)
                .map(ApiResponse::success)
                .switchIfEmpty(Mono.just(ApiResponse.error(404, "任务不存在")));
    }

    public Mono<ApiResponse<Flux<RunInstance>>> listTasks(String status) {
        return Mono.just(ApiResponse.success(gpuSchedulerPort.listTasks(status)));
    }

    public Mono<ApiResponse<Void>> preemptTask(String taskId) {
        Map<String, Object> context = Map.of("taskId", taskId);
        logger.info("抢占GPU任务", context);

        return gpuSchedulerPort.preemptTask(taskId)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> {
                    logger.error("抢占GPU任务失败", e, context);
                    return Mono.just(ApiResponse.error(500, e.getMessage()));
                });
    }

    public Mono<ApiResponse<Map<String, Object>>> getClusterStatus() {
        return Mono.just(ApiResponse.success(gpuSchedulerPort.getClusterStatus()));
    }

    public Mono<ApiResponse<Void>> adjustTaskPriority(String taskId, int newPriority) {
        Map<String, Object> context = Map.of("taskId", taskId, "newPriority", newPriority);
        logger.info("调整任务优先级", context);

        return gpuSchedulerPort.adjustTaskPriority(taskId, newPriority)
                .then(Mono.just(ApiResponse.success(null)))
                .onErrorResume(e -> {
                    logger.error("调整任务优先级失败", e, context);
                    return Mono.just(ApiResponse.error(500, e.getMessage()));
                });
    }
}
