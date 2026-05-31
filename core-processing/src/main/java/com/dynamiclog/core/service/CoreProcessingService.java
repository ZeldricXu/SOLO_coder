package com.dynamiclog.core.service;

import com.dynamiclog.common.context.ExecutionContext;
import com.dynamiclog.common.entity.Task;
import com.dynamiclog.common.enums.TaskStatus;
import com.dynamiclog.common.event.DomainEvent;
import com.dynamiclog.common.exception.BusinessException;
import com.dynamiclog.common.exception.ValidationException;
import com.dynamiclog.common.util.IdGenerator;
import com.dynamiclog.common.util.JsonUtils;
import com.dynamiclog.config.service.ConfigManagementService;
import com.dynamiclog.persistence.mapper.TaskMapper;
import com.dynamiclog.persistence.mapper.TaskRunMapper;
import com.dynamiclog.scheduler.service.TaskSchedulerService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoreProcessingService {

    private final TaskMapper taskMapper;
    private final TaskRunMapper taskRunMapper;
    private final TaskSchedulerService schedulerService;
    private final ConfigManagementService configService;
    private final ApplicationEventPublisher eventPublisher;
    private final MeterRegistry meterRegistry;

    private final Map<String, ExecutionContext> activeContexts = new ConcurrentHashMap<>();

    private final Counter taskSubmittedCounter;
    private final Counter taskCompletedCounter;
    private final Counter taskFailedCounter;
    private final Timer taskExecutionTimer;

    @Autowired
    public CoreProcessingService(
            TaskMapper taskMapper,
            TaskRunMapper taskRunMapper,
            TaskSchedulerService schedulerService,
            ConfigManagementService configService,
            ApplicationEventPublisher eventPublisher,
            MeterRegistry meterRegistry) {
        this.taskMapper = taskMapper;
        this.taskRunMapper = taskRunMapper;
        this.schedulerService = schedulerService;
        this.configService = configService;
        this.eventPublisher = eventPublisher;
        this.meterRegistry = meterRegistry;
        this.taskSubmittedCounter = Counter.builder("tasks.submitted")
                .description("Number of tasks submitted")
                .register(meterRegistry);
        this.taskCompletedCounter = Counter.builder("tasks.completed")
                .description("Number of tasks completed")
                .register(meterRegistry);
        this.taskFailedCounter = Counter.builder("tasks.failed")
                .description("Number of tasks failed")
                .register(meterRegistry);
        this.taskExecutionTimer = Timer.builder("tasks.execution.duration")
                .description("Task execution duration")
                .register(meterRegistry);
    }

    public Mono<Map<String, Object>> executeHandler(Map<String, Object> request) {
        String traceId = request.get("traceId") != null ?
                request.get("traceId").toString() : IdGenerator.generateId("trace");

        ExecutionContext ctx = initContext(traceId);
        activeContexts.put(traceId, ctx);

        return Mono.defer(() -> {
            try {
                validateParams(request);
                ctx.getAttributes().put("validated", true);

                String namespace = request.getOrDefault("namespace", "default").toString();
                return loadConfig(namespace)
                        .flatMap(config -> {
                            ctx.getAttributes().put("config", config);
                            int poolSize = config != null ? (Integer) config.getOrDefault("poolSize", 10) : 10;
                            return acquireResource(poolSize);
                        })
                        .flatMap(resource -> {
                            ctx.getAttributes().put("resource", resource);
                            Object payload = request.get("payload");
                            Map<String, Object> rules = new HashMap<>();
                            return processCore(payload, rules)
                                    .doOnNext(result -> {
                                        persistResult(result);
                                        emitEvent("task.completed", buildEvent(result, ctx));
                                        taskCompletedCounter.increment();
                                    })
                                    .doOnError(e -> {
                                        taskFailedCounter.increment();
                                        rollbackTransaction(ctx);
                                    })
                                    .onErrorResume(e -> {
                                        if (e instanceof ValidationException) {
                                            return Mono.error(new BusinessException(422, ((ValidationException) e).getMessage()));
                                        }
                                        return Mono.error(new BusinessException(500, "内部处理错误: " + e.getMessage()));
                                    })
                                    .doFinally(s -> {
                                        releaseResource(resource);
                                        recordMetrics(ctx);
                                        ctx.cleanup();
                                        activeContexts.remove(traceId);
                                    });
                        });
            } catch (ValidationException e) {
                return Mono.error(new BusinessException(422, e.getMessage()));
            } catch (Exception e) {
                rollbackTransaction(ctx);
                return Mono.error(new BusinessException(500, "内部处理错误: " + e.getMessage()));
            }
        })
                .timeout(Duration.ofSeconds(30))
                .onErrorResume(e -> {
                    ctx.setTimeoutOccurred(true);
                    ctx.setErrorMessage(e.getMessage());
                    recordMetrics(ctx);
                    activeContexts.remove(traceId);
                    if (e.getMessage().contains("Timeout")) {
                        return Mono.error(new BusinessException(504, "上游服务响应超时"));
                    }
                    return Mono.error(e);
                });
    }

    private ExecutionContext initContext(String traceId) {
        ExecutionContext ctx = new ExecutionContext();
        ctx.setTraceId(traceId);
        taskSubmittedCounter.increment();
        return ctx;
    }

    private void validateParams(Map<String, Object> request) {
        if (request == null || request.isEmpty()) {
            throw new ValidationException("request", "Request cannot be empty");
        }
        if (request.get("payload") == null) {
            throw new ValidationException("payload", "Payload is required");
        }
    }

    private Mono<Map<String, Object>> loadConfig(String namespace) {
        return configService.getConfig("core-processing", namespace)
                .map(config -> {
                    try {
                        return JsonUtils.fromJson(config.getContent(), Map.class);
                    } catch (Exception e) {
                        return new HashMap<String, Object>();
                    }
                })
                .onErrorResume(e -> Mono.just(new HashMap<>()));
    }

    private Mono<Object> acquireResource(int poolSize) {
        return Mono.just(new Object())
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Object> processCore(Object payload, Map<String, Object> rules) {
        return Mono.fromCallable(() -> {
            long start = System.currentTimeMillis();
            try {
                Map<String, Object> result = new HashMap<>();
                result.put("originalPayload", payload);
                result.put("processed", true);
                result.put("timestamp", System.currentTimeMillis());
                result.put("rulesApplied", rules.size());

                Thread.sleep(100);

                taskExecutionTimer.record(Duration.ofMillis(System.currentTimeMillis() - start));
                return result;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Processing interrupted");
            }
        });
    }

    private void persistResult(Object result) {
        log.debug("Result persisted: {}", JsonUtils.toJson(result));
    }

    private void emitEvent(String eventType, DomainEvent event) {
        eventPublisher.publishEvent(event);
        log.debug("Event emitted: type={}", eventType);
    }

    private DomainEvent buildEvent(Object result, ExecutionContext ctx) {
        DomainEvent event = new DomainEvent();
        event.setEventId(IdGenerator.generateId("evt"));
        event.setEventType("task.completed");
        event.setSource("core-processing");
        event.setTraceId(ctx.getTraceId());

        Map<String, Object> payload = new HashMap<>();
        payload.put("result", result);
        payload.put("durationMs", ctx.getElapsedMs());
        event.setPayload(payload);

        return event;
    }

    private void releaseResource(Object resource) {
        log.debug("Resource released");
    }

    private void rollbackTransaction(ExecutionContext ctx) {
        log.warn("Transaction rolled back: traceId={}", ctx.getTraceId());
    }

    private void recordMetrics(ExecutionContext ctx) {
        log.debug("Metrics recorded: traceId={}, elapsedMs={}", ctx.getTraceId(), ctx.getElapsedMs());
    }

    public Mono<Task> submitTask(Task task) {
        return schedulerService.createTask(task)
                .flatMap(t -> schedulerService.scheduleTask(t.getId()));
    }

    public Mono<Map<String, Object>> getExecutionStatus(String traceId) {
        ExecutionContext ctx = activeContexts.get(traceId);
        if (ctx == null) {
            return Mono.just(Map.of("status", "not_found", "traceId", traceId));
        }
        return Mono.just(Map.of(
                "traceId", traceId,
                "elapsedMs", ctx.getElapsedMs(),
                "timeoutOccurred", ctx.isTimeoutOccurred(),
                "error", ctx.getErrorMessage() != null ? ctx.getErrorMessage() : "",
                "attributes", ctx.getAttributes()
        ));
    }

    public Mono<Map<String, Object>> getSystemStatus() {
        return Mono.just(Map.of(
                "activeTasks", activeContexts.size(),
                "taskSubmitted", taskSubmittedCounter.count(),
                "taskCompleted", taskCompletedCounter.count(),
                "taskFailed", taskFailedCounter.count()
        ));
    }

    public Mono<Void> cancelExecution(String traceId) {
        return Mono.fromRunnable(() -> {
            ExecutionContext ctx = activeContexts.get(traceId);
            if (ctx != null) {
                ctx.setTimeoutOccurred(true);
                ctx.setErrorMessage("Cancelled by user");
                activeContexts.remove(traceId);
                log.info("Execution cancelled: traceId={}", traceId);
            }
        });
    }
}
