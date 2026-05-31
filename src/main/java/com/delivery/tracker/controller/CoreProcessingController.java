package com.delivery.tracker.controller;

import com.delivery.tracker.async.AsyncTaskCallback;
import com.delivery.tracker.async.AsyncTaskContext;
import com.delivery.tracker.common.Result;
import com.delivery.tracker.service.CoreProcessingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/process")
@RequiredArgsConstructor
public class CoreProcessingController {

    private final CoreProcessingService coreProcessingService;

    /**
     * 同步处理请求（原有接口，保持稳定）
     */
    @PostMapping("/execute")
    public Mono<Result<Map<String, Object>>> executeHandler(@RequestBody Map<String, Object> request) {
        String traceId = (String) request.getOrDefault("traceId", UUID.randomUUID().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", new HashMap<>());
        String namespace = (String) request.getOrDefault("namespace", "default");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) request.getOrDefault("payload", new HashMap<>());

        return coreProcessingService.executeHandler(traceId, params, namespace, payload)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /**
     * 异步处理请求（新接口：回调方式）
     * 立即返回任务ID，结果通过回调或事件通知
     */
    @PostMapping("/execute/async")
    public Mono<Result<Map<String, Object>>> executeHandlerAsync(@RequestBody Map<String, Object> request) {
        String traceId = (String) request.getOrDefault("traceId", UUID.randomUUID().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", new HashMap<>());
        String namespace = (String) request.getOrDefault("namespace", "default");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) request.getOrDefault("payload", new HashMap<>());
        long timeoutMs = ((Number) request.getOrDefault("timeoutMs", 0)).longValue();

        String taskId = coreProcessingService.executeHandlerAsync(
                traceId, params, namespace, payload,
                new AsyncTaskCallback() {
                    @Override
                    public void onStarted(AsyncTaskContext context) {
                        log.info("任务开始执行: taskId={}", context.getTaskId());
                    }

                    @Override
                    public void onCompleted(AsyncTaskContext context, Object result) {
                        log.info("任务执行完成: taskId={}, 耗时={}ms",
                                context.getTaskId(), context.getElapsedMs());
                    }

                    @Override
                    public void onFailed(AsyncTaskContext context, Throwable throwable) {
                        log.error("任务执行失败: taskId={}, error={}",
                                context.getTaskId(), throwable.getMessage());
                    }

                    @Override
                    public void onCancelled(AsyncTaskContext context) {
                        log.warn("任务被取消: taskId={}", context.getTaskId());
                    }

                    @Override
                    public void onTimeout(AsyncTaskContext context) {
                        log.warn("任务超时: taskId={}", context.getTaskId());
                    }
                },
                timeoutMs
        );

        Map<String, Object> response = new HashMap<>();
        response.put("taskId", taskId);
        response.put("traceId", traceId);
        response.put("status", "ACCEPTED");
        response.put("message", "任务已提交，可通过/tasks/{taskId}查询状态");

        return Mono.just(Result.success(response));
    }

    /**
     * 异步处理请求（新接口：响应式方式）
     * 订阅 Mono 获得最终结果
     */
    @PostMapping("/execute/reactive")
    public Mono<Result<AsyncTaskContext>> executeHandlerReactive(@RequestBody Map<String, Object> request) {
        String traceId = (String) request.getOrDefault("traceId", UUID.randomUUID().toString());
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) request.getOrDefault("params", new HashMap<>());
        String namespace = (String) request.getOrDefault("namespace", "default");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) request.getOrDefault("payload", new HashMap<>());
        long timeoutMs = ((Number) request.getOrDefault("timeoutMs", 30000)).longValue();

        return coreProcessingService.executeHandlerReactive(traceId, params, namespace, payload, timeoutMs)
                .map(Result::success)
                .onErrorResume(e -> Mono.just(Result.error(e.getMessage())));
    }

    /**
     * 查询异步任务状态（新接口）
     */
    @GetMapping("/tasks/{taskId}")
    public Mono<Result<AsyncTaskContext>> getTaskStatus(@PathVariable String taskId) {
        return coreProcessingService.getAsyncTaskStatus(taskId)
                .map(context -> {
                    if (context == null) {
                        return Result.<AsyncTaskContext>error("任务不存在或已过期");
                    }
                    return Result.success(context);
                });
    }

    /**
     * 取消异步任务（新接口）
     */
    @DeleteMapping("/tasks/{taskId}")
    public Mono<Result<Boolean>> cancelTask(@PathVariable String taskId) {
        return coreProcessingService.cancelAsyncTask(taskId)
                .map(cancelled -> {
                    if (!cancelled) {
                        return Result.<Boolean>error("任务不存在或已完成");
                    }
                    return Result.success(true);
                });
    }
}
