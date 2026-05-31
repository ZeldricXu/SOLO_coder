package com.chainetl.common.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.chainetl.common.dto.BatchOperationRequest;
import com.chainetl.common.dto.BatchOperationResponse;
import com.chainetl.common.dto.ResourceRequest;
import com.chainetl.common.dto.ResourceStatus;
import com.chainetl.common.exception.BusinessException;
import com.chainetl.common.exception.TimeoutException;
import com.chainetl.common.mapper.ConfigDefinitionMapper;
import com.chainetl.common.mapper.CoreEntityMapper;
import com.chainetl.common.mapper.RunInstanceMapper;
import com.chainetl.common.model.ConfigDefinition;
import com.chainetl.common.model.CoreEntity;
import com.chainetl.common.model.RunInstance;
import com.chainetl.common.util.IdGenerator;
import io.github.resilience4j.retry.annotation.Retry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class ResourceManagementService {

    private final CoreEntityMapper entityMapper;
    private final ConfigDefinitionMapper configMapper;
    private final RunInstanceMapper runInstanceMapper;
    private final MeterRegistry meterRegistry;

    private final Map<String, String> traceContext = new ConcurrentHashMap<>();

    @Transactional
    @Retry(name = "resource", fallbackMethod = "createResourceFallback")
    public Mono<Map<String, Object>> createResource(ResourceRequest request) {
        return Mono.fromCallable(() -> {
            String traceId = java.util.UUID.randomUUID().toString();
            traceContext.put("traceId", traceId);

            try {
                validateParams(request);
                ConfigDefinition config = loadConfig("default");
                Map<String, Object> result = processCore(request, config);
                persistResult(result, request);
                emitEvent("resource.created", buildEvent(result));

                log.info("Created resource: type={}, traceId={}", request.getType(), traceId);

                return Map.of(
                        "id", (String) result.get("resourceId"),
                        "status", "provisioning"
                );
            } catch (BusinessException e) {
                throw e;
            } catch (java.util.concurrent.TimeoutException e) {
                throw new TimeoutException("上游服务响应超时");
            } catch (Exception e) {
                rollbackTransaction(traceId);
                throw new BusinessException("内部处理错误: " + e.getMessage());
            } finally {
                recordMetrics(traceId);
                traceContext.remove(traceId);
            }
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<ResourceStatus> getResourceStatus(String id) {
        return Mono.fromCallable(() -> {
            CoreEntity entity = entityMapper.selectById(id);
            if (entity == null) {
                throw new BusinessException(404, "Resource not found: " + id);
            }

            LambdaQueryWrapper<RunInstance> runWrapper = new LambdaQueryWrapper<>();
            runWrapper.eq(RunInstance::getEntityId, id)
                    .orderByDesc(RunInstance::getStartedAt)
                    .last("LIMIT 1");
            RunInstance runInstance = runInstanceMapper.selectOne(runWrapper);

            double progress = runInstance != null && runInstance.getProgress() != null ?
                    runInstance.getProgress() : 0.0;

            return ResourceStatus.builder()
                    .id(entity.getId())
                    .status(entity.getStatus())
                    .progress(progress)
                    .build();
        });
    }

    @Transactional
    @Retry(name = "resource", fallbackMethod = "batchOperationFallback")
    public Mono<BatchOperationResponse> executeBatchOperation(BatchOperationRequest request) {
        return Mono.fromCallable(() -> {
            String batchId = IdGenerator.generateBatchId();
            List<Map<String, Object>> results = new ArrayList<>();

            for (BatchOperationRequest.Operation op : request.getOperations()) {
                try {
                    Map<String, Object> result = executeSingleOperation(op);
                    results.add(Map.of(
                            "id", op.getId(),
                            "action", op.getAction(),
                            "success", true,
                            "result", result
                    ));
                } catch (Exception e) {
                    results.add(Map.of(
                            "id", op.getId(),
                            "action", op.getAction(),
                            "success", false,
                            "error", e.getMessage()
                    ));
                }
            }

            log.info("Executed batch operation: batchId={}, operations={}", batchId, request.getOperations().size());

            return BatchOperationResponse.builder()
                    .batchId(batchId)
                    .results(results)
                    .build();
        });
    }

    private void validateParams(ResourceRequest request) {
        if (request.getType() == null || request.getType().isEmpty()) {
            throw new BusinessException(422, "Resource type is required");
        }
    }

    private ConfigDefinition loadConfig(String namespace) {
        LambdaQueryWrapper<ConfigDefinition> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ConfigDefinition::getNamespace, namespace)
                .eq(ConfigDefinition::getEnabled, true)
                .orderByDesc(ConfigDefinition::getVersion)
                .last("LIMIT 1");

        ConfigDefinition config = configMapper.selectOne(wrapper);
        if (config == null) {
            config = ConfigDefinition.builder()
                    .configId(IdGenerator.generateConfigId())
                    .namespace(namespace)
                    .version(1)
                    .parameters(Map.of("timeout", 30, "retries", 3))
                    .enabled(true)
                    .appliedAt(Instant.now())
                    .build();
        }
        return config;
    }

    private Map<String, Object> processCore(ResourceRequest request, ConfigDefinition config) {
        String resourceId = IdGenerator.generateResourceId();
        log.debug("Processing resource: type={}, configVersion={}", request.getType(), config.getVersion());

        return Map.of(
                "resourceId", resourceId,
                "type", request.getType(),
                "config", request.getConfig() != null ? request.getConfig() : Map.of(),
                "labels", request.getLabels() != null ? request.getLabels() : Map.of()
        );
    }

    private void persistResult(Map<String, Object> result, ResourceRequest request) {
        Instant now = Instant.now();
        String resourceId = (String) result.get("resourceId");

        CoreEntity entity = CoreEntity.builder()
                .id(resourceId)
                .type(request.getType())
                .status("provisioning")
                .attributes(request.getConfig())
                .createdAt(now)
                .updatedAt(now)
                .build();
        entityMapper.insert(entity);

        RunInstance runInstance = RunInstance.builder()
                .runId(IdGenerator.generateRunId())
                .entityId(resourceId)
                .phase("initializing")
                .progress(0.0)
                .startedAt(now)
                .build();
        runInstanceMapper.insert(runInstance);
    }

    private void emitEvent(String eventType, Map<String, Object> eventData) {
        log.info("Emitting event: type={}, data={}", eventType, eventData);
    }

    private Map<String, Object> buildEvent(Map<String, Object> result) {
        return Map.of(
                "resourceId", result.get("resourceId"),
                "timestamp", Instant.now().toString(),
                "type", result.get("type")
        );
    }

    private Map<String, Object> executeSingleOperation(BatchOperationRequest.Operation op) {
        String action = op.getAction().toLowerCase();
        return switch (action) {
            case "start" -> Map.of("status", "started", "startedAt", Instant.now().toString());
            case "stop" -> Map.of("status", "stopped", "stoppedAt", Instant.now().toString());
            case "restart" -> Map.of("status", "restarted", "restartedAt", Instant.now().toString());
            case "delete" -> {
                entityMapper.deleteById(op.getId());
                yield Map.of("status", "deleted", "deletedAt", Instant.now().toString());
            }
            default -> throw new BusinessException(400, "Unsupported action: " + action);
        };
    }

    private void rollbackTransaction(String traceId) {
        log.warn("Rolling back transaction for traceId: {}", traceId);
    }

    private void recordMetrics(String traceId) {
        Timer.Sample sample = Timer.start(meterRegistry);
        Timer timer = meterRegistry.timer("resource.operation", "traceId", traceId);
        sample.stop(timer);
    }

    private Mono<Map<String, Object>> createResourceFallback(ResourceRequest request, Exception e) {
        log.error("Create resource fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to create resource after retries: " + e.getMessage());
    }

    private Mono<BatchOperationResponse> batchOperationFallback(BatchOperationRequest request, Exception e) {
        log.error("Batch operation fallback triggered: {}", e.getMessage(), e);
        throw new BusinessException("Failed to execute batch operation after retries: " + e.getMessage());
    }
}
