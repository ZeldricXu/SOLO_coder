package com.datapipeline.core;

import com.datapipeline.common.event.Event;
import com.datapipeline.common.event.EventPublisher;
import com.datapipeline.common.exception.BusinessException;
import com.datapipeline.common.exception.ValidationError;
import com.datapipeline.common.model.ConfigDefinition;
import com.datapipeline.common.model.Entity;
import com.datapipeline.common.model.RunInstance;
import com.datapipeline.common.tracing.TraceContext;
import com.datapipeline.core.config.CachedConfigParser;
import com.datapipeline.core.config.ProcessConfig;
import com.datapipeline.core.error.ErrorHandler;
import com.datapipeline.core.metrics.MetricsRecorder;
import com.datapipeline.core.persistence.ResultPersister;
import com.datapipeline.core.resource.HighPerformanceResourcePool;
import com.datapipeline.core.resource.PooledResource;
import com.datapipeline.core.transform.DataTransformer;
import com.datapipeline.core.validation.ParameterValidator;
import com.datapipeline.data.repository.ConfigRepository;
import com.datapipeline.data.repository.ResourceRepository;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
public class HighPerformanceCoreProcessor {

    private final ParameterValidator validator;
    private final ConfigRepository configRepository;
    private final ResourceRepository resourceRepository;
    private final HighPerformanceResourcePool resourcePool;
    private final DataTransformer transformer;
    private final ResultPersister persister;
    private final EventPublisher eventPublisher;
    private final MetricsRecorder metricsRecorder;
    private final CachedConfigParser configParser;
    private final ErrorHandler errorHandler;

    public HighPerformanceCoreProcessor(ParameterValidator validator,
                                        ConfigRepository configRepository,
                                        ResourceRepository resourceRepository,
                                        HighPerformanceResourcePool resourcePool,
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
        this.configParser = new CachedConfigParser();
        this.errorHandler = new ErrorHandler(metricsRecorder, persister);
    }

    public ProcessResult execute(RequestContext ctx) {
        String requestId = ctx.getRequestId();
        String traceId = ctx.getTraceId();
        String namespace = ctx.getNamespace();

        TraceContext traceCtx = TraceContext.create("core_process", traceId);
        traceCtx.tag("requestId", requestId);
        traceCtx.tag("namespace", namespace);

        PooledResource resource = null;

        try {
            validator.validate(ctx.getParams());

            ConfigDefinition rawConfig = configRepository.findLatestByNamespace(namespace)
                    .orElseGet(() -> createDefaultConfig(namespace));
            ctx.setConfig(rawConfig);

            ProcessConfig config = configParser.parse(rawConfig);
            validator.validateConfig(rawConfig.getParameters());

            resource = acquireResource(config);

            RunInstance runInstance = persister.createRun(namespace);
            ctx.setResource(Entity.builder()
                    .id(resource.getId())
                    .type("processor")
                    .status("active")
                    .build());

            return processWithContext(ctx, resource, runInstance, config, traceCtx);

        } catch (ValidationError e) {
            return errorHandler.handleValidationError(requestId, e, traceCtx);

        } catch (BusinessException e) {
            return errorHandler.handleBusinessException(requestId, e, traceCtx);

        } catch (Exception e) {
            ErrorHandler.safeRollback(() -> rollbackTransaction(ctx), requestId);
            return errorHandler.handleUnexpectedError(requestId, e, traceCtx);

        } finally {
            safeRelease(resource);
            metricsRecorder.recordTraceContext(traceCtx);
            TraceContext.clear();
        }
    }

    private PooledResource acquireResource(ProcessConfig config) throws InterruptedException, TimeoutException {
        try {
            return resourcePool.acquire(config.getAcquireTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BusinessException.internalError("Resource acquisition interrupted");
        }
    }

    private ProcessResult processWithContext(RequestContext ctx,
                                             PooledResource resource,
                                             RunInstance runInstance,
                                             ProcessConfig config,
                                             TraceContext traceCtx) {
        String requestId = ctx.getRequestId();

        persister.markRunning(runInstance.getRunId());

        if (ctx.isTimedOut() || ctx.isCancelled()) {
            throw BusinessException.timeout("Processing timeout or cancelled");
        }

        Object result = transformPayload(ctx.getPayload(), config);
        persister.updateProgress(runInstance.getRunId(), 0.7);

        persister.persistSuccess(runInstance.getRunId(), result);
        persister.updateProgress(runInstance.getRunId(), 1.0);

        emitEvent("task.completed", buildEvent(ctx, result, runInstance));

        traceCtx.markSuccess();

        return ProcessResult.success(requestId, result)
                .toBuilder()
                .durationMs(traceCtx.durationMillis())
                .build();
    }

    private Object transformPayload(Object payload, ProcessConfig config) {
        if (payload == null) {
            return Collections.emptyMap();
        }
        Object transformed = transformer.transform(payload, config.getTransformRules());
        log.debug("Core processing completed, transformation rules applied: {}",
                config.getTransformRules().size());
        return transformed;
    }

    private ConfigDefinition createDefaultConfig(String namespace) {
        ConfigDefinition config = ConfigDefinition.builder()
                .configId("cfg_default_" + namespace)
                .namespace(namespace)
                .version(1)
                .enabled(true)
                .parameter("timeout", ProcessConfig.DEFAULT_TIMEOUT_SECONDS)
                .parameter("retries", ProcessConfig.DEFAULT_MAX_RETRIES)
                .parameter("poolSize", ProcessConfig.DEFAULT_POOL_SIZE)
                .parameter("acquireTimeoutMs", ProcessConfig.DEFAULT_ACQUIRE_TIMEOUT_MS)
                .appliedAt(Instant.now())
                .build();
        configRepository.save(config);
        log.info("Created default config for namespace: {}", namespace);
        return config;
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

    private void safeRelease(PooledResource resource) {
        if (resource != null) {
            try {
                resourcePool.release(resource);
            } catch (Exception e) {
                log.error("Failed to release resource: id={}", resource.getId(), e);
            }
        }
    }

}
