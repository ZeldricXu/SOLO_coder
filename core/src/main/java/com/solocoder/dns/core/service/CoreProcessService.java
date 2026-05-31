package com.solocoder.dns.core.service;

import com.solocoder.dns.common.entity.RunInstance;
import com.solocoder.dns.common.enums.EntityStatus;
import com.solocoder.dns.common.enums.ExecutionPhase;
import com.solocoder.dns.common.exception.BusinessException;
import com.solocoder.dns.common.exception.ValidationException;
import com.solocoder.dns.common.util.IdGenerator;
import com.solocoder.dns.common.util.JsonUtils;
import com.solocoder.dns.config.service.ConfigService;
import com.solocoder.dns.core.model.ExecutionContext;
import com.solocoder.dns.core.model.ProcessRequest;
import com.solocoder.dns.core.model.ProcessResult;
import com.solocoder.dns.persistence.repository.RunInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoreProcessService {
    private final ConfigService configService;
    private final RunInstanceRepository runInstanceRepository;
    private final EventPublisher eventPublisher;

    private final Semaphore resourceSemaphore = new Semaphore(100);
    private final Map<String, Object> resourcePool = new ConcurrentHashMap<>();

    public Mono<ProcessResult> execute(ProcessRequest request) {
        ExecutionContext ctx = initContext(request);

        return Mono.<ProcessResult>create(sink -> {
            try {
                validateParams(request.getParams());
                Map<String, Object> config = loadConfig(request.getNamespace());
                Object resource = acquireResource(config);

                try {
                    RunInstance runInstance = createRunInstance(request, ctx);
                    updatePhase(runInstance, ExecutionPhase.PROCESSING, 0.25);

                    Map<String, Object> result = processCore(request.getPayload(), config);
                    updatePhase(runInstance, ExecutionPhase.FINALIZING, 0.75);

                    persistResult(runInstance, result);
                    updatePhase(runInstance, ExecutionPhase.COMPLETED, 1.0);

                    eventPublisher.emitEvent("task.completed", buildEvent(runInstance, result));

                    sink.success(buildSuccessResponse(runInstance, result, ctx));
                } finally {
                    releaseResource(resource);
                }
            } catch (ValidationException e) {
                log.warn("Validation failed: {}", e.getMessage());
                sink.success(buildErrorResponse(422, e.getDetails(), ctx));
            } catch (BusinessException e) {
                log.error("Business error: {}", e.getMessage());
                sink.success(buildErrorResponse(e.getCode(), e.getMessage(), ctx));
            } catch (Exception e) {
                log.error("Unexpected error during processing", e);
                rollbackTransaction(ctx);
                sink.success(buildErrorResponse(500, "内部处理错误", ctx));
            } finally {
                recordMetrics(ctx);
                ctx.cleanup();
            }
        });
    }

    private ExecutionContext initContext(ProcessRequest request) {
        ExecutionContext ctx = new ExecutionContext();
        ctx.setTraceId(request.getTraceId() != null ? request.getTraceId() : IdGenerator.generateTraceId());
        ctx.setRunId(IdGenerator.generateRunId());
        ctx.setNamespace(request.getNamespace());
        ctx.setUserId(request.getUserId());
        return ctx;
    }

    private void validateParams(Map<String, Object> params) {
        if (params == null) {
            throw new ValidationException("params", "参数不能为空");
        }
    }

    private Map<String, Object> loadConfig(String namespace) {
        return configService.getMergedParameters(namespace);
    }

    private Object acquireResource(Map<String, Object> config) {
        int poolSize = config.containsKey("poolSize") ? ((Number) config.get("poolSize")).intValue() : 10;
        try {
            if (!resourceSemaphore.tryAcquire()) {
                throw new BusinessException(503, "资源不足，请稍后重试");
            }
            return new Object();
        } catch (Exception e) {
            throw new BusinessException(503, "获取资源失败: " + e.getMessage());
        }
    }

    private void releaseResource(Object resource) {
        resourceSemaphore.release();
    }

    private RunInstance createRunInstance(ProcessRequest request, ExecutionContext ctx) {
        RunInstance instance = new RunInstance();
        instance.setRunId(ctx.getRunId());
        instance.setEntityId(IdGenerator.generateEntityId());
        instance.setPhase(ExecutionPhase.INITIALIZING.name());
        instance.setProgress(0.0);
        instance.setStartedAt(LocalDateTime.now());
        return runInstanceRepository.save(instance);
    }

    private void updatePhase(RunInstance instance, ExecutionPhase phase, double progress) {
        instance.setPhase(phase.name());
        instance.setProgress(progress);
        runInstanceRepository.update(instance);
    }

    private Map<String, Object> processCore(Map<String, Object> payload, Map<String, Object> config) {
        Map<String, Object> result = new ConcurrentHashMap<>();
        result.put("processed", true);
        result.put("timestamp", System.currentTimeMillis());
        result.put("input", payload);
        result.put("rulesApplied", config.getOrDefault("rules", "default"));
        return result;
    }

    private void persistResult(RunInstance instance, Map<String, Object> result) {
        instance.setCompletedAt(LocalDateTime.now());
        instance.setStatus(EntityStatus.COMPLETED.name());
        runInstanceRepository.update(instance);
        log.debug("Result persisted for run: {}", instance.getRunId());
    }

    private Map<String, Object> buildEvent(RunInstance instance, Map<String, Object> result) {
        return Map.of(
                "eventType", "task.completed",
                "runId", instance.getRunId(),
                "entityId", instance.getEntityId(),
                "result", result,
                "timestamp", LocalDateTime.now().toString()
        );
    }

    private ProcessResult buildSuccessResponse(RunInstance instance, Map<String, Object> data, ExecutionContext ctx) {
        ProcessResult result = new ProcessResult();
        result.setRunId(instance.getRunId());
        result.setStatus(EntityStatus.COMPLETED.name());
        result.setMessage("success");
        result.setData(data);
        result.setElapsedMs(ctx.getElapsedMs());
        return result;
    }

    private ProcessResult buildErrorResponse(int code, String message, ExecutionContext ctx) {
        ProcessResult result = new ProcessResult();
        result.setRunId(ctx.getRunId());
        result.setStatus("ERROR");
        result.setMessage(message);
        result.setElapsedMs(ctx.getElapsedMs());
        return result;
    }

    private void rollbackTransaction(ExecutionContext ctx) {
        log.warn("Rolling back transaction for trace: {}", ctx.getTraceId());
        ctx.getTransactionState().clear();
    }

    private void recordMetrics(ExecutionContext ctx) {
        log.debug("Recording metrics for trace: {}, elapsed: {}ms", ctx.getTraceId(), ctx.getElapsedMs());
    }
}
