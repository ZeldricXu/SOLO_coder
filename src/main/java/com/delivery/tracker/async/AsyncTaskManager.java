package com.delivery.tracker.async;

import com.delivery.tracker.entity.AsyncTask;
import com.delivery.tracker.mapper.AsyncTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * 异步任务管理器
 * 负责异步任务的提交、调度、监控和生命周期管理
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AsyncTaskManager {

    private final ApplicationEventPublisher eventPublisher;
    private final AsyncTaskMapper asyncTaskMapper;

    private final ExecutorService executorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2,
            r -> {
                Thread t = new Thread(r);
                t.setName("async-task-" + t.getId());
                t.setDaemon(true);
                return t;
            }
    );

    private final Map<String, AsyncTaskContext> runningTasks = new ConcurrentHashMap<>();

    /**
     * 提交异步任务（回调方式）
     * 保持原同步接口签名不变
     */
    public String submitTask(
            String traceId,
            Map<String, Object> params,
            String namespace,
            Map<String, Object> payload,
            Function<AsyncTaskContext, Map<String, Object>> taskFunction,
            AsyncTaskCallback callback) {

        return submitTask(traceId, params, namespace, payload, taskFunction, callback, 0);
    }

    /**
     * 提交异步任务（带超时，回调方式）
     */
    public String submitTask(
            String traceId,
            Map<String, Object> params,
            String namespace,
            Map<String, Object> payload,
            Function<AsyncTaskContext, Map<String, Object>> taskFunction,
            AsyncTaskCallback callback,
            long timeoutMs) {

        String taskId = UUID.randomUUID().toString();

        AsyncTaskContext context = AsyncTaskContext.builder()
                .taskId(taskId)
                .traceId(traceId)
                .namespace(namespace)
                .params(params)
                .payload(payload)
                .status(AsyncTaskStatus.PENDING)
                .createdAt(LocalDateTime.now())
                .timeoutMs(timeoutMs)
                .build();

        runningTasks.put(taskId, context);

        // 保存任务记录
        saveTaskRecord(context);

        AsyncTaskCallback actualCallback = callback != null ? callback : AsyncTaskCallback.EMPTY;

        executorService.submit(() -> executeTask(context, taskFunction, actualCallback));

        log.debug("异步任务已提交: taskId={}, traceId={}", taskId, traceId);
        return taskId;
    }

    /**
     * 提交异步任务（返回 Mono，事件通知方式）
     * 新接口，支持响应式编程
     */
    public Mono<AsyncTaskContext> submitTaskReactive(
            String traceId,
            Map<String, Object> params,
            String namespace,
            Map<String, Object> payload,
            Function<AsyncTaskContext, Map<String, Object>> taskFunction,
            long timeoutMs) {

        return Mono.<AsyncTaskContext>create(sink -> {
            String taskId = submitTask(traceId, params, namespace, payload, taskFunction,
                    new AsyncTaskCallback() {
                        @Override
                        public void onStarted(AsyncTaskContext ctx) {
                        }

                        @Override
                        public void onCompleted(AsyncTaskContext ctx, Object result) {
                            sink.success(ctx);
                        }

                        @Override
                        public void onFailed(AsyncTaskContext ctx, Throwable throwable) {
                            sink.error(throwable);
                        }

                        @Override
                        public void onCancelled(AsyncTaskContext ctx) {
                            sink.error(new RuntimeException("任务被取消"));
                        }

                        @Override
                        public void onTimeout(AsyncTaskContext ctx) {
                            sink.error(new RuntimeException("任务超时"));
                        }
                    },
                    timeoutMs);

            // 立即返回任务上下文
            sink.success(runningTasks.get(taskId));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private void executeTask(
            AsyncTaskContext context,
            Function<AsyncTaskContext, Map<String, Object>> taskFunction,
            AsyncTaskCallback callback) {

        context.setStatus(AsyncTaskStatus.RUNNING);
        context.setStartedAt(LocalDateTime.now());
        updateTaskRecord(context);

        try {
            callback.onStarted(context);
            publishEvent(context, AsyncTaskEvent.EventType.STARTED);

            // 检查超时
            if (context.getTimeoutMs() > 0) {
                startTimeoutMonitor(context, callback);
            }

            // 执行任务
            Map<String, Object> result = taskFunction.apply(context);

            if (context.isCancelled()) {
                callback.onCancelled(context);
                publishEvent(context, AsyncTaskEvent.EventType.CANCELLED);
                return;
            }

            context.setResult(result);
            context.setStatus(AsyncTaskStatus.COMPLETED);
            context.setCompletedAt(LocalDateTime.now());
            callback.onCompleted(context, result);
            publishEvent(context, AsyncTaskEvent.EventType.COMPLETED, result);

        } catch (Throwable t) {
            log.error("异步任务执行失败: taskId={}", context.getTaskId(), t);
            context.setError(t);
            context.setErrorMessage(t.getMessage());
            context.setStatus(AsyncTaskStatus.FAILED);
            context.setCompletedAt(LocalDateTime.now());
            callback.onFailed(context, t);
            publishEvent(context, AsyncTaskEvent.EventType.FAILED, t);
        } finally {
            updateTaskRecord(context);
            // 任务结束后保留5分钟用于查询，之后清理
            scheduleCleanup(context.getTaskId(), 300000);
        }
    }

    private void startTimeoutMonitor(AsyncTaskContext context, AsyncTaskCallback callback) {
        Thread.startVirtualThread(() -> {
            try {
                long remaining = context.getTimeoutMs();
                while (remaining > 0 && context.getStatus() == AsyncTaskStatus.RUNNING) {
                    Thread.sleep(Math.min(remaining, 1000));
                    if (context.isCancelled()) {
                        return;
                    }
                    remaining = context.getTimeoutMs() - context.getElapsedMs();
                }
                if (context.getStatus() == AsyncTaskStatus.RUNNING && !context.isCancelled()) {
                    context.setStatus(AsyncTaskStatus.TIMEOUT);
                    context.cancel();
                    callback.onTimeout(context);
                    publishEvent(context, AsyncTaskEvent.EventType.TIMEOUT);
                    log.warn("异步任务超时: taskId={}", context.getTaskId());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public Mono<AsyncTaskContext> getTaskStatus(String taskId) {
        AsyncTaskContext context = runningTasks.get(taskId);
        if (context != null) {
            return Mono.just(context);
        }
        return Mono.fromCallable(() -> {
            AsyncTask record = asyncTaskMapper.selectById(taskId);
            if (record == null) {
                return null;
            }
            return convertToContext(record);
        });
    }

    public Mono<Boolean> cancelTask(String taskId) {
        AsyncTaskContext context = runningTasks.get(taskId);
        if (context != null) {
            context.cancel();
            return Mono.just(true);
        }
        return Mono.just(false);
    }

    public Flux<AsyncTaskContext> getRunningTasks() {
        return Flux.fromIterable(runningTasks.values());
    }

    private void saveTaskRecord(AsyncTaskContext context) {
        try {
            AsyncTask record = new AsyncTask();
            record.setTaskId(context.getTaskId());
            record.setTraceId(context.getTraceId());
            record.setNamespace(context.getNamespace());
            record.setStatus(context.getStatus().name());
            record.setCreatedAt(context.getCreatedAt());
            asyncTaskMapper.insert(record);
        } catch (Exception e) {
            log.warn("保存任务记录失败", e);
        }
    }

    private void updateTaskRecord(AsyncTaskContext context) {
        try {
            AsyncTask record = new AsyncTask();
            record.setTaskId(context.getTaskId());
            record.setStatus(context.getStatus().name());
            record.setStartedAt(context.getStartedAt());
            record.setCompletedAt(context.getCompletedAt());
            record.setErrorMessage(context.getErrorMessage());
            record.setUpdatedAt(LocalDateTime.now());
            asyncTaskMapper.updateById(record);
        } catch (Exception e) {
            log.warn("更新任务记录失败", e);
        }
    }

    private AsyncTaskContext convertToContext(AsyncTask record) {
        return AsyncTaskContext.builder()
                .taskId(record.getTaskId())
                .traceId(record.getTraceId())
                .namespace(record.getNamespace())
                .status(AsyncTaskStatus.valueOf(record.getStatus()))
                .errorMessage(record.getErrorMessage())
                .createdAt(record.getCreatedAt())
                .startedAt(record.getStartedAt())
                .completedAt(record.getCompletedAt())
                .build();
    }

    private void publishEvent(AsyncTaskContext context, AsyncTaskEvent.EventType type) {
        publishEvent(context, type, null);
    }

    private void publishEvent(AsyncTaskContext context, AsyncTaskEvent.EventType type, Object payload) {
        try {
            eventPublisher.publishEvent(new AsyncTaskEvent(this, context.getTaskId(), context, type, payload));
        } catch (Exception e) {
            log.warn("发布任务事件失败", e);
        }
    }

    private void scheduleCleanup(String taskId, long delayMs) {
        Thread.startVirtualThread(() -> {
            try {
                Thread.sleep(delayMs);
                runningTasks.remove(taskId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    public void shutdown() {
        executorService.shutdownNow();
    }
}
