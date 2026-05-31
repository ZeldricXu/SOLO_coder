package com.datapipeline.core;

import com.datapipeline.common.event.Event;
import com.datapipeline.common.event.EventPublisher;
import com.datapipeline.common.exception.BusinessException;
import com.datapipeline.common.exception.ValidationError;
import com.datapipeline.common.model.ConfigDefinition;
import com.datapipeline.common.model.Entity;
import com.datapipeline.common.model.RunInstance;
import com.datapipeline.common.tracing.TraceContext;
import com.datapipeline.core.metrics.MetricsRecorder;
import com.datapipeline.core.persistence.ResultPersister;
import com.datapipeline.core.resource.PooledResource;
import com.datapipeline.core.resource.ResourcePool;
import com.datapipeline.core.transform.DataTransformer;
import com.datapipeline.core.transform.TransformRule;
import com.datapipeline.core.validation.ParameterValidator;
import com.datapipeline.data.repository.ConfigRepository;
import com.datapipeline.data.repository.ResourceRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class CoreProcessor {

    private final ParameterValidator validator;
    private final ConfigRepository configRepository;
    private final ResourceRepository resourceRepository;
    private final ResourcePool resourcePool;
    private final DataTransformer transformer;
    private final ResultPersister persister;
    private final EventPublisher eventPublisher;
    private final MetricsRecorder metricsRecorder;

    public CoreProcessor(ParameterValidator validator,
                         ConfigRepository configRepository,
                         ResourceRepository resourceRepository,
                         ResourcePool resourcePool,
                         DataTransformer transformer,
                         ResultPersister persister,
                         EventPublisher eventPublisher,
                         MetricsRecorder metricsRecorder) {
        this.validator = validator;
        this.configRepository = configRepository;
        this.resourceRepository = resourceRepository;
        this.resourcePool = resourcePool;
        this.transformer = transformer;
        this.persister = persister;
        this.eventPublisher = eventPublisher;
        this.metricsRecorder = metricsRecorder;
    }

    public ProcessResult execute(RequestContext ctx) {
        String requestId = ctx.getRequestId();
        String traceId = ctx.getTraceId();

        TraceContext traceCtx = TraceContext.create("core_process", traceId);
        traceCtx.tag("requestId", requestId);
        traceCtx.tag("namespace", ctx.getNamespace());

        try {
            log.info("Processing request: requestId={}, traceId={}", requestId, traceId);

            validator.validate(ctx.getParams());

            ConfigDefinition config = configRepository.findLatestByNamespace(ctx.getNamespace())
                    .orElseGet(() -> createDefaultConfig(ctx.getNamespace()));
            ctx.setConfig(config);
            validator.validateConfig(config.getParameters());

            int poolSize = getPoolSize(config);
            long acquireTimeout = getAcquireTimeout(config);

            PooledResource resource;
            try {
                resource = resourcePool.acquire(acquireTimeout, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw BusinessException.internalError("Resource acquisition interrupted");
            } catch (TimeoutException e) {
                traceCtx.markError("RESOURCE_ACQUIRE_TIMEOUT");
                metricsRecorder.recordTraceContext(traceCtx);
                persister.persistTimeout(requestId, "Resource acquisition timeout");
                return ProcessResult.timeout(requestId, "上游服务响应超时");
            }

            RunInstance runInstance = persister.createRun(ctx.getNamespace());
            ctx.setResource(Entity.builder()
                    .id(resource.getId())
                    .type("processor")
                    .status("active")
                    .build());

            try {
                persister.markRunning(runInstance.getRunId());

                if (ctx.isTimedOut() || ctx.isCancelled()) {
                    throw BusinessException.timeout("Processing timeout or cancelled");
                }

                Object result = processCore(ctx.getPayload(), extractRules(config));
                persister.updateProgress(runInstance.getRunId(), 0.7);

                persister.persistSuccess(runInstance.getRunId(), result);
                persister.updateProgress(runInstance.getRunId(), 1.0);

                emitEvent("task.completed", buildEvent(ctx, result, runInstance));

                traceCtx.markSuccess();
                metricsRecorder.recordTraceContext(traceCtx);

                return ProcessResult.success(requestId, result)
                        .toBuilder()
                        .durationMs(traceCtx.durationMillis())
                        .build();

            } finally {
                resourcePool.release(resource);
            }

        } catch (ValidationError e) {
            log.warn("Validation error: requestId={}, detail={}", requestId, e.getMessage());
            traceCtx.markError("VALIDATION");
            metricsRecorder.recordTraceContext(traceCtx);
            return ProcessResult.error(requestId, "Validation failed", e.getMessage());

        } catch (BusinessException e) {
            log.error("Business error: requestId={}, code={}, message={}", requestId, e.getCode(), e.getMessage());
            if (e.getCode() == 504) {
                traceCtx.markError("TIMEOUT");
                metricsRecorder.recordTraceContext(traceCtx);
                persister.persistTimeout(requestId, e.getMessage());
                return ProcessResult.timeout(requestId, "上游服务响应超时");
            }
            traceCtx.markError("BUSINESS_" + e.getCode());
            metricsRecorder.recordTraceContext(traceCtx);
            return ProcessResult.error(requestId, e.getMessage(), e.getErrorDetail());

        } catch (Exception e) {
            log.error("Unexpected error during processing: requestId={}", requestId, e);
            rollbackTransaction(ctx);
            traceCtx.markError("INTERNAL");
            metricsRecorder.recordTraceContext(traceCtx);
            return ProcessResult.error(requestId, "内部处理错误", e.getMessage());

        } finally {
            TraceContext.clear();
        }
    }

    private Object processCore(Object payload, List<TransformRule> rules) {
        if (payload == null) {
            return Collections.emptyMap();
        }
        Object transformed = transformer.transform(payload, rules);
        log.debug("Core processing completed, transformation rules applied: {}", rules.size());
        return transformed;
    }

    private ConfigDefinition createDefaultConfig(String namespace) {
        ConfigDefinition config = ConfigDefinition.builder()
                .configId("cfg_default_" + namespace)
                .namespace(namespace)
                .version(1)
                .enabled(true)
                .parameter("timeout", 30)
                .parameter("retries", 3)
                .parameter("poolSize", 10)
                .parameter("acquireTimeoutMs", 5000)
                .appliedAt(Instant.now())
                .build();
        configRepository.save(config);
        return config;
    }

    private int getPoolSize(ConfigDefinition config) {
        Object poolSize = config.getParameters().get("poolSize");
        if (poolSize instanceof Number) {
            return ((Number) poolSize).intValue();
        }
        return 10;
    }

    private long getAcquireTimeout(ConfigDefinition config) {
        Object timeout = config.getParameters().get("acquireTimeoutMs");
        if (timeout instanceof Number) {
            return ((Number) timeout).longValue();
        }
        return 5000;
    }

    @SuppressWarnings("unchecked")
    private List<TransformRule> extractRules(ConfigDefinition config) {
        Object rulesObj = config.getParameters().get("rules");
        if (rulesObj instanceof List<?>) {
            List<TransformRule> rules = new ArrayList<>();
            for (Object item : (List<?>) rulesObj) {
                if (item instanceof Map<?, ?> map) {
                    try {
                        TransformRule rule = TransformRule.builder()
                                .type(TransformRule.RuleType.valueOf((String) map.get("type")))
                                .params((Map<String, Object>) map.getOrDefault("params", Collections.emptyMap()))
                                .build();
                        rules.add(rule);
                    } catch (Exception e) {
                        log.warn("Invalid transform rule: {}", item, e);
                    }
                }
            }
            return rules;
        }
        return Collections.emptyList();
    }

    private Event buildEvent(RequestContext ctx, Object result, RunInstance run) {
        return Event.builder()
                .type("task.completed")
                .source("core-processor")
                .traceId(ctx.getTraceId())
                .payload(Map.of(
                        "requestId", ctx.getRequestId(),
                        "runId", run.getRunId(),
                        "namespace", ctx.getNamespace(),
                        "result", result
                ))
                .build();
    }

    private void emitEvent(String type, Event event) {
        try {
            eventPublisher.publish(event);
        } catch (Exception e) {
            log.warn("Failed to emit event: {}", type, e);
        }
    }

    private void rollbackTransaction(RequestContext ctx) {
        log.warn("Rolling back transaction for request: {}", ctx.getRequestId());
    }

}
