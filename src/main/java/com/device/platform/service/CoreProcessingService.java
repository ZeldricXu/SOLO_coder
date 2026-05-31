package com.device.platform.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.device.platform.common.*;
import com.device.platform.dto.*;
import com.device.platform.entity.ConfigDefinition;
import com.device.platform.entity.RunInstance;
import com.device.platform.mapper.ConfigDefinitionMapper;
import com.device.platform.mapper.RunInstanceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
@RequiredArgsConstructor
public class CoreProcessingService {

    private final ConfigDefinitionMapper configDefinitionMapper;
    private final RunInstanceMapper runInstanceMapper;
    private final MetricsService metricsService;

    private final Semaphore processingSemaphore = new Semaphore(100);

    public Mono<ProcessResponse> executeHandler(ProcessRequest request) {
        TraceContext ctx = new TraceContext(request.getTraceId());

        return Mono.<ProcessResponse>create(sink -> {
            ctx.putAttribute("namespace", request.getNamespace());
            ctx.putAttribute("entityType", request.getEntityType());
            ctx.putAttribute("entityId", request.getEntityId());

            RunInstance runInstance = initRunInstance(request, ctx);

            try {
                if (!processingSemaphore.tryAcquire()) {
                    throw new BusinessException(429, "系统繁忙，请稍后重试", ctx.getTraceId());
                }

                try {
                    updateRunPhase(runInstance, RunPhase.VALIDATING, EntityStatus.PROCESSING, 0.1);
                    validateParams(request.getParams());

                    updateRunPhase(runInstance, RunPhase.EXECUTING, EntityStatus.PROCESSING, 0.3);
                    ConfigDefinition config = loadConfig(request.getNamespace());

                    updateRunPhase(runInstance, RunPhase.EXECUTING, EntityStatus.PROCESSING, 0.5);
                    Map<String, Object> result = processCore(request.getPayload(), config);

                    updateRunPhase(runInstance, RunPhase.PERSISTING, EntityStatus.PROCESSING, 0.8);
                    persistResult(runInstance, result);

                    updateRunPhase(runInstance, RunPhase.COMPLETED, EntityStatus.SUCCESS, 1.0);
                    emitEvent("task.completed", buildEvent(runInstance, result));

                    ProcessResponse response = buildSuccessResponse(runInstance, result, ctx);
                    sink.success(response);

                } catch (BusinessException e) {
                    handleBusinessException(runInstance, e, ctx);
                    sink.error(e);
                } catch (Exception e) {
                    handleSystemException(runInstance, e, ctx);
                    sink.error(new BusinessException(500, "内部处理错误", e));
                } finally {
                    processingSemaphore.release();
                    recordMetrics(ctx, runInstance);
                }
            } catch (Exception e) {
                sink.error(e);
            }
        }).subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> Mono.just(buildErrorResponse(e, ctx)));
    }

    private RunInstance initRunInstance(ProcessRequest request, TraceContext ctx) {
        String runId = generateRunId();

        RunInstance runInstance = new RunInstance();
        runInstance.setRunId(runId);
        runInstance.setEntityId(request.getEntityId());
        runInstance.setEntityType(request.getEntityType());
        runInstance.setPhase(RunPhase.INIT);
        runInstance.setStatus(EntityStatus.PENDING);
        runInstance.setProgress(0.0);
        runInstance.setStartedAt(Instant.now());
        runInstance.setTraceId(ctx.getTraceId());
        runInstance.setConfigSnapshot(JsonUtils.toJson(request.getParams()));

        runInstanceMapper.insert(runInstance);
        ctx.putAttribute("runId", runId);

        log.info("处理实例初始化: runId={}, traceId={}", runId, ctx.getTraceId());
        return runInstance;
    }

    private void validateParams(Map<String, Object> params) {
        if (params == null) {
            return;
        }

        if (params.containsKey("timeout")) {
            Object timeout = params.get("timeout");
            if (timeout instanceof Number && ((Number) timeout).intValue() <= 0) {
                throw new BusinessException(422, "参数错误: timeout必须大于0");
            }
        }

        if (params.containsKey("retries")) {
            Object retries = params.get("retries");
            if (retries instanceof Number) {
                int retryCount = ((Number) retries).intValue();
                if (retryCount < 0 || retryCount > 10) {
                    throw new BusinessException(422, "参数错误: retries必须在0-10之间");
                }
            }
        }
    }

    private ConfigDefinition loadConfig(String namespace) {
        ConfigDefinition config = configDefinitionMapper.selectOne(new LambdaQueryWrapper<ConfigDefinition>()
                .eq(ConfigDefinition::getNamespace, namespace)
                .eq(ConfigDefinition::isEnabled, true)
                .orderByDesc(ConfigDefinition::getVersion)
                .last("LIMIT 1"));

        if (config == null) {
            ConfigDefinition defaultConfig = new ConfigDefinition();
            defaultConfig.setConfigId("cfg_default");
            defaultConfig.setNamespace(namespace);
            defaultConfig.setVersion(1);
            defaultConfig.setEnabled(true);

            Map<String, Object> defaultParams = new HashMap<>();
            defaultParams.put("timeout", 30);
            defaultParams.put("retries", 3);
            defaultConfig.setParameters(JsonUtils.toJson(defaultParams));

            log.warn("未找到命名空间配置，使用默认配置: namespace={}", namespace);
            return defaultConfig;
        }

        return config;
    }

    private Map<String, Object> processCore(Map<String, Object> payload, ConfigDefinition config) {
        Map<String, Object> parameters = JsonUtils.fromJson(config.getParameters(), Map.class);

        Map<String, Object> result = new HashMap<>();
        result.put("processed", true);
        result.put("processedAt", Instant.now().toString());
        result.put("configVersion", config.getVersion());
        result.put("namespace", config.getNamespace());

        int timeout = Optional.ofNullable(parameters.get("timeout"))
                .map(v -> ((Number) v).intValue())
                .orElse(30);
        int retries = Optional.ofNullable(parameters.get("retries"))
                .map(v -> ((Number) v).intValue())
                .orElse(3);

        result.put("timeoutApplied", timeout);
        result.put("retriesApplied", retries);

        Map<String, Object> transformedPayload = transformPayload(payload);
        result.put("output", transformedPayload);

        result.put("metadata", buildMetadata(payload, parameters));

        return result;
    }

    private Map<String, Object> transformPayload(Map<String, Object> payload) {
        Map<String, Object> transformed = new LinkedHashMap<>();

        for (Map.Entry<String, Object> entry : payload.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof String) {
                transformed.put(entry.getKey(), ((String) value).trim());
            } else if (value instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> nestedMap = (Map<String, Object>) value;
                transformed.put(entry.getKey(), transformPayload(nestedMap));
            } else {
                transformed.put(entry.getKey(), value);
            }
        }

        transformed.put("_transformed", true);
        return transformed;
    }

    private Map<String, Object> buildMetadata(Map<String, Object> payload, Map<String, Object> parameters) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("inputSize", payload.size());
        metadata.put("parameterCount", parameters.size());
        metadata.put("processingId", UUID.randomUUID().toString());
        return metadata;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void persistResult(RunInstance runInstance, Map<String, Object> result) {
        runInstance.setResultData(JsonUtils.toJson(result));
        runInstance.setCompletedAt(Instant.now());
        runInstanceMapper.updateById(runInstance);
        log.debug("结果持久化完成: runId={}", runInstance.getRunId());
    }

    private void updateRunPhase(RunInstance runInstance, RunPhase phase, EntityStatus status, double progress) {
        runInstance.setPhase(phase);
        runInstance.setStatus(status);
        runInstance.setProgress(progress);
        runInstanceMapper.updateById(runInstance);
        log.debug("运行阶段更新: runId={}, phase={}, status={}, progress={}",
                runInstance.getRunId(), phase, status, progress);
    }

    private void emitEvent(String eventType, Map<String, Object> event) {
        log.info("事件发布: type={}, event={}", eventType, JsonUtils.toJson(event));
    }

    private Map<String, Object> buildEvent(RunInstance runInstance, Map<String, Object> result) {
        Map<String, Object> event = new HashMap<>();
        event.put("eventType", "task.completed");
        event.put("runId", runInstance.getRunId());
        event.put("entityId", runInstance.getEntityId());
        event.put("entityType", runInstance.getEntityType());
        event.put("timestamp", Instant.now().toString());
        event.put("result", result);
        return event;
    }

    private ProcessResponse buildSuccessResponse(RunInstance runInstance, Map<String, Object> result, TraceContext ctx) {
        ProcessResponse response = new ProcessResponse();
        response.setRunId(runInstance.getRunId());
        response.setEntityId(runInstance.getEntityId());
        response.setStatus(EntityStatus.SUCCESS.name());
        response.setResult(result);
        response.setTraceId(ctx.getTraceId());
        response.setCreatedAt(Instant.now());
        response.setDurationMs(ctx.getDurationMs());
        return response;
    }

    private ProcessResponse buildErrorResponse(Throwable e, TraceContext ctx) {
        ProcessResponse response = new ProcessResponse();
        response.setStatus(EntityStatus.FAILED.name());
        response.setTraceId(ctx.getTraceId());
        response.setCreatedAt(Instant.now());
        response.setDurationMs(ctx.getDurationMs());

        Map<String, Object> errorResult = new HashMap<>();
        errorResult.put("error", e.getMessage());
        errorResult.put("errorType", e.getClass().getSimpleName());
        response.setResult(errorResult);

        return response;
    }

    private void handleBusinessException(RunInstance runInstance, BusinessException e, TraceContext ctx) {
        runInstance.setPhase(RunPhase.ERROR);
        runInstance.setStatus(EntityStatus.FAILED);
        runInstance.setErrorDetail(e.getMessage());
        runInstance.setCompletedAt(Instant.now());
        runInstanceMapper.updateById(runInstance);

        log.error("业务异常: runId={}, code={}, message={}, traceId={}",
                runInstance.getRunId(), e.getCode(), e.getMessage(), ctx.getTraceId());
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    protected void handleSystemException(RunInstance runInstance, Exception e, TraceContext ctx) {
        rollbackTransaction(runInstance);

        runInstance.setPhase(RunPhase.ERROR);
        runInstance.setStatus(EntityStatus.ROLLBACK);
        runInstance.setErrorDetail(e.getMessage());
        runInstance.setCompletedAt(Instant.now());
        runInstanceMapper.updateById(runInstance);

        log.error("系统异常，执行回滚: runId={}, error={}, traceId={}",
                runInstance.getRunId(), e.getMessage(), ctx.getTraceId(), e);
    }

    protected void rollbackTransaction(RunInstance runInstance) {
        log.warn("事务回滚: runId={}", runInstance.getRunId());
    }

    private void recordMetrics(TraceContext ctx, RunInstance runInstance) {
        long duration = ctx.getDurationMs();
        String status = runInstance.getStatus() != null ? runInstance.getStatus().name() : "UNKNOWN";

        Map<String, String> tags = new HashMap<>();
        tags.put("entityType", runInstance.getEntityType() != null ? runInstance.getEntityType() : "unknown");
        tags.put("status", status);
        tags.put("namespace", (String) ctx.getAttributes().get("namespace"));

        metricsService.recordMetric("request.duration", duration, tags);
        metricsService.incrementCounter("request.count", tags);

        if ("FAILED".equals(status) || "ROLLBACK".equals(status)) {
            metricsService.incrementCounter("request.errors", tags);
        }

        log.debug("指标记录完成: runId={}, duration={}ms, status={}",
                runInstance.getRunId(), duration, status);
    }

    public Mono<RunStatusResponse> getRunStatus(String runId, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            RunInstance runInstance = runInstanceMapper.selectOne(new LambdaQueryWrapper<RunInstance>()
                    .eq(RunInstance::getRunId, runId));

            if (runInstance == null) {
                throw new BusinessException(404, "运行实例不存在: " + runId, ctx.getTraceId());
            }

            RunStatusResponse response = new RunStatusResponse();
            response.setRunId(runInstance.getRunId());
            response.setEntityId(runInstance.getEntityId());
            response.setPhase(runInstance.getPhase() != null ? runInstance.getPhase().name() : null);
            response.setStatus(runInstance.getStatus() != null ? runInstance.getStatus().name() : null);
            response.setProgress(runInstance.getProgress());
            response.setStartedAt(runInstance.getStartedAt());
            response.setCompletedAt(runInstance.getCompletedAt());
            response.setErrorDetail(runInstance.getErrorDetail());

            return response;
        });
    }

    public Mono<BatchOperationResponse> executeBatch(BatchOperationRequest request, TraceContext ctx) {
        return Mono.fromCallable(() -> {
            String batchId = generateBatchId();
            ctx.putAttribute("batchId", batchId);

            List<BatchOperationResponse.OperationResult> results = new ArrayList<>();

            for (BatchOperationRequest.BatchOperation operation : request.getOperations()) {
                BatchOperationResponse.OperationResult result = new BatchOperationResponse.OperationResult();
                result.setId(operation.getId());
                result.setAction(operation.getAction());

                try {
                    Object operationResult = executeSingleOperation(operation, ctx);
                    result.setCode(200);
                    result.setMessage("success");
                    result.setData(operationResult);
                } catch (Exception e) {
                    result.setCode(500);
                    result.setMessage(e.getMessage());
                    log.error("批量操作失败: batchId={}, action={}, id={}, error={}",
                            batchId, operation.getAction(), operation.getId(), e.getMessage());
                }

                results.add(result);
            }

            BatchOperationResponse response = new BatchOperationResponse();
            response.setBatchId(batchId);
            response.setResults(results);

            log.info("批量操作完成: batchId={}, total={}, traceId={}",
                    batchId, request.getOperations().size(), ctx.getTraceId());

            return response;
        });
    }

    private Object executeSingleOperation(BatchOperationRequest.BatchOperation operation, TraceContext ctx) {
        Map<String, Object> result = new HashMap<>();
        result.put("id", operation.getId());
        result.put("action", operation.getAction());
        result.put("executed", true);
        result.put("params", operation.getParams());
        return result;
    }

    private String generateRunId() {
        return "run_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private String generateBatchId() {
        return "batch_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }
}
