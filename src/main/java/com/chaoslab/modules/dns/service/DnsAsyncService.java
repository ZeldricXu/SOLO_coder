package com.chaoslab.modules.dns.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chaoslab.entity.DnsAsyncTask;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.DnsAsyncTaskMapper;
import com.chaoslab.modules.dns.callback.DnsResolveCallback;
import com.chaoslab.modules.dns.callback.WebhookCallbackHandler;
import com.chaoslab.modules.dns.dto.AsyncDnsResolveRequest;
import com.chaoslab.modules.dns.dto.AsyncDnsTaskResponse;
import com.chaoslab.modules.dns.dto.DnsResolveRequest;
import com.chaoslab.modules.dns.dto.DnsResolveResponse;
import com.chaoslab.modules.dns.event.DnsResolveEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Service
@RequiredArgsConstructor
public class DnsAsyncService {

    private final DnsProxyService dnsProxyService;
    private final DnsAsyncTaskMapper asyncTaskMapper;
    private final WebhookCallbackHandler webhookCallbackHandler;
    private final ApplicationEventPublisher eventPublisher;

    private final Map<String, DnsResolveCallback> callbackRegistry = new ConcurrentHashMap<>();
    private final PriorityBlockingQueue<PrioritizedTask> taskQueue = new PriorityBlockingQueue<>(1000);
    private final ExecutorService executorService = Executors.newFixedThreadPool(
            Runtime.getRuntime().availableProcessors() * 2,
            r -> {
                Thread t = new Thread(r, "dns-async-worker-" + new AtomicInteger(0).incrementAndGet());
                t.setDaemon(true);
                return t;
            });

    private volatile boolean running = true;

    @javax.annotation.PostConstruct
    public void init() {
        for (int i = 0; i < Runtime.getRuntime().availableProcessors(); i++) {
            executorService.submit(this::processTaskLoop);
        }
        log.info("DnsAsyncService initialized with {} worker threads", Runtime.getRuntime().availableProcessors());
    }

    @javax.annotation.PreDestroy
    public void shutdown() {
        running = false;
        executorService.shutdown();
        log.info("DnsAsyncService shutdown complete");
    }

    // ==================== 异步请求提交 ====================

    @Transactional
    public Mono<AsyncDnsTaskResponse> submitAsyncResolve(AsyncDnsResolveRequest request) {
        return Mono.fromCallable(() -> {
            validateAsyncRequest(request);

            DnsAsyncTask task = new DnsAsyncTask();
            task.setTaskId("dat-" + UUID.randomUUID().toString().substring(0, 8));
            task.setRequestId("req-" + UUID.randomUUID().toString().substring(0, 12));
            task.setDomain(request.getDomain());
            task.setQueryType(request.getQueryType() != null ? request.getQueryType() : "A");
            task.setStatus("PENDING");
            task.setPriority(request.getPriority() != null ? request.getPriority() : "normal");
            task.setCallbackType(request.getCallbackType());
            task.setCallbackUrl(request.getCallbackUrl());
            task.setCallbackHeaders(request.getCallbackHeaders());
            task.setEventName(request.getEventName());
            task.setEventPayload(request.getEventPayload());
            task.setRetryCount(0);
            task.setMaxRetries(request.getMaxRetries() != null ? request.getMaxRetries() : 3);
            task.setSubmittedAt(LocalDateTime.now());
            task.setRequestedBy(request.getRequestedBy());
            task.setContext(request.getContext());

            asyncTaskMapper.insert(task);

            taskQueue.put(new PrioritizedTask(task, getPriorityValue(task.getPriority())));

            log.info("Submitted async DNS task: {} for domain: {}, priority: {}",
                    task.getTaskId(), task.getDomain(), task.getPriority());

            return toTaskResponse(task);
        });
    }

    public Mono<AsyncDnsTaskResponse> submitAsyncResolveWithCallback(
            AsyncDnsResolveRequest request, DnsResolveCallback callback) {
        return submitAsyncResolve(request)
                .doOnNext(response -> {
                    if (callback != null) {
                        callbackRegistry.put(response.getTaskId(), callback);
                    }
                });
    }

    // ==================== 任务查询 ====================

    public Mono<AsyncDnsTaskResponse> getTaskStatus(String taskId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<DnsAsyncTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DnsAsyncTask::getTaskId, taskId);
            DnsAsyncTask task = asyncTaskMapper.selectOne(wrapper);
            if (task == null) {
                throw BusinessException.notFound("异步任务不存在: " + taskId);
            }
            return toTaskResponse(task);
        });
    }

    public Mono<DnsResolveResponse> getTaskResult(String taskId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<DnsAsyncTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DnsAsyncTask::getTaskId, taskId);
            DnsAsyncTask task = asyncTaskMapper.selectOne(wrapper);
            if (task == null) {
                throw BusinessException.notFound("异步任务不存在: " + taskId);
            }
            if (!"COMPLETED".equals(task.getStatus())) {
                throw BusinessException.validationError("任务尚未完成，当前状态: " + task.getStatus());
            }

            DnsResolveResponse response = new DnsResolveResponse();
            response.setDomain(task.getDomain());
            response.setQueryType(task.getQueryType());
            response.setUpstreamId(task.getUpstreamId());
            if (task.getResult() != null) {
                response.setAnswers((List<String>) task.getResult().get("answers"));
                response.setTtl((Integer) task.getResult().get("ttl"));
                response.setFromCache((Boolean) task.getResult().getOrDefault("fromCache", false));
            }
            response.setResolvedAt(task.getCompletedAt());

            return response;
        });
    }

    public Flux<AsyncDnsTaskResponse> listTasks(String status, String domain, int pageNum, int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<DnsAsyncTask> wrapper = new LambdaQueryWrapper<>();
            if (status != null && !status.isEmpty()) {
                wrapper.eq(DnsAsyncTask::getStatus, status);
            }
            if (domain != null && !domain.isEmpty()) {
                wrapper.like(DnsAsyncTask::getDomain, domain);
            }
            wrapper.orderByDesc(DnsAsyncTask::getSubmittedAt)
                    .last("LIMIT " + pageSize + " OFFSET " + (pageNum - 1) * pageSize);
            return asyncTaskMapper.selectList(wrapper);
        }).flatMapMany(Flux::fromIterable)
                .map(this::toTaskResponse);
    }

    // ==================== 任务处理 ====================

    private void processTaskLoop() {
        while (running || !taskQueue.isEmpty()) {
            try {
                PrioritizedTask prioritizedTask = taskQueue.poll(1, java.util.concurrent.TimeUnit.SECONDS);
                if (prioritizedTask != null) {
                    processTask(prioritizedTask.task);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.error("Error processing DNS task", e);
            }
        }
    }

    private void processTask(DnsAsyncTask task) {
        try {
            updateTaskStatus(task, "PROCESSING");
            task.setStartedAt(LocalDateTime.now());
            asyncTaskMapper.updateById(task);

            DnsResolveRequest request = new DnsResolveRequest();
            request.setDomain(task.getDomain());
            request.setQueryType(task.getQueryType());
            request.setForceRefresh(false);

            DnsResolveResponse response = dnsProxyService.resolve(request)
                    .timeout(Duration.ofMillis(task.getMaxRetries() != null ? task.getMaxRetries() * 2000L : 5000L))
                    .onErrorResume(e -> {
                        if (task.getRetryCount() < (task.getMaxRetries() != null ? task.getMaxRetries() : 3)) {
                            return handleRetry(task, request, e);
                        }
                        return Mono.error(e);
                    })
                    .block();

            task.setCompletedAt(LocalDateTime.now());
            task.setDurationMs(java.time.Duration.between(task.getStartedAt(), task.getCompletedAt()).toMillis());
            task.setUpstreamId(response.getUpstreamId());

            Map<String, Object> result = new HashMap<>();
            result.put("answers", response.getAnswers());
            result.put("ttl", response.getTtl());
            result.put("upstreamId", response.getUpstreamId());
            result.put("fromCache", response.isFromCache());
            result.put("resolvedAt", response.getResolvedAt());
            task.setResult(result);

            updateTaskStatus(task, "COMPLETED");
            asyncTaskMapper.updateById(task);

            notifySuccess(task, response);

            log.info("Async DNS task completed: {} for domain: {}, duration: {}ms",
                    task.getTaskId(), task.getDomain(), task.getDurationMs());

        } catch (Exception e) {
            handleTaskFailure(task, e);
        }
    }

    private Mono<DnsResolveResponse> handleRetry(DnsAsyncTask task, DnsResolveRequest request, Throwable originalError) {
        task.setRetryCount(task.getRetryCount() + 1);
        asyncTaskMapper.updateById(task);

        log.warn("Retrying DNS task: {}, attempt: {}, error: {}",
                task.getTaskId(), task.getRetryCount(), originalError.getMessage());

        eventPublisher.publishEvent(DnsResolveEvent.retry(this, task, task.getRetryCount()));

        return dnsProxyService.resolve(request)
                .delaySubscription(Duration.ofMillis(100L * task.getRetryCount()));
    }

    private void handleTaskFailure(DnsAsyncTask task, Throwable error) {
        try {
            task.setCompletedAt(LocalDateTime.now());
            task.setErrorMessage(error.getMessage());
            if (task.getStartedAt() != null) {
                task.setDurationMs(java.time.Duration.between(task.getStartedAt(), task.getCompletedAt()).toMillis());
            }

            updateTaskStatus(task, "FAILED");
            asyncTaskMapper.updateById(task);

            notifyFailure(task, error);

            log.error("Async DNS task failed: {} for domain: {}, error: {}",
                    task.getTaskId(), task.getDomain(), error.getMessage());

        } catch (Exception e) {
            log.error("Error handling task failure for task: {}", task.getTaskId(), e);
        }
    }

    // ==================== 通知机制 ====================

    private void notifySuccess(DnsAsyncTask task, DnsResolveResponse response) {
        DnsResolveCallback callback = callbackRegistry.remove(task.getTaskId());
        if (callback != null) {
            try {
                callback.onComplete(task, response, null);
            } catch (Exception e) {
                log.error("Callback execution failed for task: {}", task.getTaskId(), e);
            }
        }

        if ("webhook".equalsIgnoreCase(task.getCallbackType())) {
            webhookCallbackHandler.handleWebhook(task, response, null);
        }

        String eventName = task.getEventName() != null ? task.getEventName() : "DNS_RESOLVE_SUCCESS";
        eventPublisher.publishEvent(DnsResolveEvent.success(this, task, response));
    }

    private void notifyFailure(DnsAsyncTask task, Throwable error) {
        DnsResolveCallback callback = callbackRegistry.remove(task.getTaskId());
        if (callback != null) {
            try {
                callback.onComplete(task, null, error);
            } catch (Exception e) {
                log.error("Callback execution failed for task: {}", task.getTaskId(), e);
            }
        }

        if ("webhook".equalsIgnoreCase(task.getCallbackType())) {
            webhookCallbackHandler.handleWebhook(task, null, error);
        }

        eventPublisher.publishEvent(DnsResolveEvent.failure(this, task, error));
    }

    // ==================== 批量操作 ====================

    public Flux<AsyncDnsTaskResponse> submitBatchResolve(List<AsyncDnsResolveRequest> requests) {
        return Flux.fromIterable(requests)
                .flatMap(request -> submitAsyncResolve(request)
                        .subscribeOn(Schedulers.boundedElastic()), 10);
    }

    public Mono<Map<String, Object>> cancelTask(String taskId) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<DnsAsyncTask> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(DnsAsyncTask::getTaskId, taskId);
            DnsAsyncTask task = asyncTaskMapper.selectOne(wrapper);
            if (task == null) {
                throw BusinessException.notFound("异步任务不存在: " + taskId);
            }

            if (!"PENDING".equals(task.getStatus()) && !"PROCESSING".equals(task.getStatus())) {
                throw BusinessException.validationError("无法取消已完成或失败的任务");
            }

            callbackRegistry.remove(taskId);
            updateTaskStatus(task, "CANCELLED");
            asyncTaskMapper.updateById(task);

            log.info("Cancelled async DNS task: {}", taskId);

            Map<String, Object> result = new HashMap<>();
            result.put("taskId", taskId);
            result.put("status", "CANCELLED");
            result.put("message", "任务已取消");
            return result;
        });
    }

    // ==================== 统计信息 ====================

    public Mono<Map<String, Object>> getAsyncStats() {
        return Mono.fromCallable(() -> {
            Map<String, Object> stats = new HashMap<>();

            LambdaQueryWrapper<DnsAsyncTask> allWrapper = new LambdaQueryWrapper<>();
            stats.put("totalTasks", asyncTaskMapper.selectCount(allWrapper));

            LambdaQueryWrapper<DnsAsyncTask> pendingWrapper = new LambdaQueryWrapper<>();
            pendingWrapper.eq(DnsAsyncTask::getStatus, "PENDING");
            stats.put("pendingTasks", asyncTaskMapper.selectCount(pendingWrapper));

            LambdaQueryWrapper<DnsAsyncTask> processingWrapper = new LambdaQueryWrapper<>();
            processingWrapper.eq(DnsAsyncTask::getStatus, "PROCESSING");
            stats.put("processingTasks", asyncTaskMapper.selectCount(processingWrapper));

            LambdaQueryWrapper<DnsAsyncTask> completedWrapper = new LambdaQueryWrapper<>();
            completedWrapper.eq(DnsAsyncTask::getStatus, "COMPLETED");
            stats.put("completedTasks", asyncTaskMapper.selectCount(completedWrapper));

            LambdaQueryWrapper<DnsAsyncTask> failedWrapper = new LambdaQueryWrapper<>();
            failedWrapper.eq(DnsAsyncTask::getStatus, "FAILED");
            stats.put("failedTasks", asyncTaskMapper.selectCount(failedWrapper));

            stats.put("queueSize", taskQueue.size());
            stats.put("activeWorkers", Runtime.getRuntime().availableProcessors());
            stats.put("registeredCallbacks", callbackRegistry.size());

            return stats;
        });
    }

    // ==================== 回调注册 ====================

    public void registerCallback(String taskId, DnsResolveCallback callback) {
        callbackRegistry.put(taskId, callback);
    }

    public void unregisterCallback(String taskId) {
        callbackRegistry.remove(taskId);
    }

    // ==================== 私有方法 ====================

    private void validateAsyncRequest(AsyncDnsResolveRequest request) {
        if (request.getDomain() == null || request.getDomain().isEmpty()) {
            throw BusinessException.validationError("域名不能为空");
        }
        if (request.getMaxRetries() != null && request.getMaxRetries() > 10) {
            throw BusinessException.validationError("最大重试次数不能超过10次");
        }
    }

    private void updateTaskStatus(DnsAsyncTask task, String status) {
        task.setStatus(status);
        log.debug("Task {} status changed to {}", task.getTaskId(), status);
    }

    private int getPriorityValue(String priority) {
        return switch (priority != null ? priority.toLowerCase() : "normal") {
            case "high" -> 1;
            case "normal" -> 5;
            case "low" -> 10;
            default -> 5;
        };
    }

    private AsyncDnsTaskResponse toTaskResponse(DnsAsyncTask task) {
        AsyncDnsTaskResponse response = new AsyncDnsTaskResponse();
        BeanUtils.copyProperties(task, response);
        return response;
    }

    @Async
    public void processPendingTasks() {
        log.info("Processing pending tasks, queue size: {}", taskQueue.size());
    }

    private record PrioritizedTask(DnsAsyncTask task, int priority)
            implements Comparable<PrioritizedTask> {
        @Override
        public int compareTo(PrioritizedTask other) {
            return Integer.compare(this.priority, other.priority);
        }
    }
}
