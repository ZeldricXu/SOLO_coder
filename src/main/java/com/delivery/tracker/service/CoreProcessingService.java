package com.delivery.tracker.service;

import com.delivery.tracker.async.AsyncTaskCallback;
import com.delivery.tracker.async.AsyncTaskContext;
import com.delivery.tracker.async.AsyncTaskManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 核心处理服务
 * 支持同步和异步两种执行模式，结果通过回调或事件通知
 * 原同步接口保持稳定，新增异步接口
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CoreProcessingService {

    private final AsyncTaskManager asyncTaskManager;

    /**
     * 同步执行处理（原有接口，保持稳定）
     * 内部直接调用同步逻辑，不做任何行为变更
     */
    public Mono<Map<String, Object>> executeHandler(
            String traceId,
            Map<String, Object> params,
            String namespace,
            Map<String, Object> payload) {

        return validateParams(params)
                .flatMap(validParams -> processCore(payload, validParams))
                .doOnSuccess(result -> log.debug("同步处理完成, traceId={}", traceId))
                .doOnError(e -> log.error("同步处理失败, traceId={}", traceId, e));
    }

    /**
     * 异步执行处理（新接口：回调方式）
     * 立即返回任务ID，结果通过回调通知
     */
    public String executeHandlerAsync(
            String traceId,
            Map<String, Object> params,
            String namespace,
            Map<String, Object> payload,
            AsyncTaskCallback callback) {

        return asyncTaskManager.submitTask(
                traceId,
                params,
                namespace,
                payload,
                context -> {
                    try {
                        return validateParams(context.getParams())
                                .flatMap(validParams -> processCore(context.getPayload(), validParams))
                                .block();
                    } catch (Exception e) {
                        throw new RuntimeException("任务执行失败", e);
                    }
                },
                callback
        );
    }

    /**
     * 异步执行处理（新接口：带超时，回调方式）
     */
    public String executeHandlerAsync(
            String traceId,
            Map<String, Object> params,
            String namespace,
            Map<String, Object> payload,
            AsyncTaskCallback callback,
            long timeoutMs) {

        return asyncTaskManager.submitTask(
                traceId,
                params,
                namespace,
                payload,
                context -> {
                    try {
                        return validateParams(context.getParams())
                                .flatMap(validParams -> processCore(context.getPayload(), validParams))
                                .block();
                    } catch (Exception e) {
                        throw new RuntimeException("任务执行失败", e);
                    }
                },
                callback,
                timeoutMs
        );
    }

    /**
     * 异步执行处理（新接口：响应式方式）
     * 返回 Mono<AsyncTaskContext>，可订阅获取完成状态
     */
    public Mono<AsyncTaskContext> executeHandlerReactive(
            String traceId,
            Map<String, Object> params,
            String namespace,
            Map<String, Object> payload,
            long timeoutMs) {

        return asyncTaskManager.submitTaskReactive(
                traceId,
                params,
                namespace,
                payload,
                context -> {
                    try {
                        return validateParams(context.getParams())
                                .flatMap(validParams -> processCore(context.getPayload(), validParams))
                                .block();
                    } catch (Exception e) {
                        throw new RuntimeException("任务执行失败", e);
                    }
                },
                timeoutMs
        );
    }

    /**
     * 获取异步任务状态（新接口）
     */
    public Mono<AsyncTaskContext> getAsyncTaskStatus(String taskId) {
        return asyncTaskManager.getTaskStatus(taskId);
    }

    /**
     * 取消异步任务（新接口）
     */
    public Mono<Boolean> cancelAsyncTask(String taskId) {
        return asyncTaskManager.cancelTask(taskId);
    }

    /**
     * 参数校验（保持内部逻辑不变）
     */
    private Mono<Map<String, Object>> validateParams(Map<String, Object> params) {
        if (params == null) {
            return Mono.error(new IllegalArgumentException("参数不能为空"));
        }
        return Mono.just(params);
    }

    /**
     * 核心处理逻辑（保持内部逻辑不变）
     */
    private Mono<Map<String, Object>> processCore(Map<String, Object> payload, Map<String, Object> params) {
        return Mono.fromCallable(() -> {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("processed", true);
            result.put("processedAt", LocalDateTime.now().toString());
            result.put("inputSize", payload != null ? payload.size() : 0);
            result.put("paramsCount", params.size());
            return result;
        });
    }
}
