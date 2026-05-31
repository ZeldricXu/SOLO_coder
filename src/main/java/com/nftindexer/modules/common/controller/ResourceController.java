package com.nftindexer.modules.common.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.nftindexer.common.ApiResponse;
import com.nftindexer.common.TraceContext;
import com.nftindexer.entity.CoreEntity;
import com.nftindexer.entity.RunInstance;
import com.nftindexer.event.DomainEvent;
import com.nftindexer.exception.BusinessException;
import com.nftindexer.mapper.CoreEntityMapper;
import com.nftindexer.mapper.RunInstanceMapper;
import com.nftindexer.modules.common.dto.ResourceBatchRequest;
import com.nftindexer.modules.common.dto.ResourceCreateRequest;
import com.nftindexer.modules.common.dto.ResourceStatusResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
public class ResourceController {

    private final CoreEntityMapper coreEntityMapper;
    private final RunInstanceMapper runInstanceMapper;
    private final Sinks.Many<DomainEvent> eventSink;

    @PostMapping
    public Mono<ApiResponse<Map<String, Object>>> createResource(
            @Valid @RequestBody ResourceCreateRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    String resourceId = "rsc_" + UUID.randomUUID().toString().substring(0, 8);

                    CoreEntity entity = new CoreEntity();
                    entity.setEntityId(resourceId);
                    entity.setType(request.getType());
                    entity.setStatus("provisioning");
                    entity.setAttributes(new HashMap<>());
                    if (request.getConfig() != null) {
                        entity.getAttributes().putAll(request.getConfig());
                    }
                    if (request.getLabels() != null) {
                        entity.getAttributes().put("labels", request.getLabels());
                    }

                    coreEntityMapper.insert(entity);

                    String runId = "run_" + UUID.randomUUID().toString().substring(0, 8);
                    RunInstance runInstance = new RunInstance();
                    runInstance.setRunId(runId);
                    runInstance.setEntityId(resourceId);
                    runInstance.setPhase("initializing");
                    runInstance.setProgress(BigDecimal.ZERO);
                    runInstance.setStartedAt(LocalDateTime.now());
                    runInstanceMapper.insert(runInstance);

                    emitEvent("resource.created", resourceId, "resource",
                            Map.of("type", request.getType(), "runId", runId), traceId);

                    log.info("Created resource: {} of type {}", resourceId, request.getType());

                    Map<String, Object> result = new HashMap<>();
                    result.put("id", resourceId);
                    result.put("status", "provisioning");
                    result.put("runId", runId);

                    return ApiResponse.created(result);
                }));
    }

    @GetMapping("/{id}/status")
    public Mono<ApiResponse<ResourceStatusResponse>> getResourceStatus(@PathVariable String id) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<CoreEntity> entityWrapper = new LambdaQueryWrapper<>();
                    entityWrapper.eq(CoreEntity::getEntityId, id);
                    CoreEntity entity = coreEntityMapper.selectOne(entityWrapper);

                    if (entity == null) {
                        throw BusinessException.notFound("资源不存在: " + id);
                    }

                    LambdaQueryWrapper<RunInstance> runWrapper = new LambdaQueryWrapper<>();
                    runWrapper.eq(RunInstance::getEntityId, id);
                    runWrapper.orderByDesc(RunInstance::getCreatedAt);
                    runWrapper.last("LIMIT 1");
                    RunInstance runInstance = runInstanceMapper.selectOne(runWrapper);

                    ResourceStatusResponse response = new ResourceStatusResponse();
                    response.setId(entity.getEntityId());
                    response.setType(entity.getType());
                    response.setStatus(entity.getStatus());
                    response.setMetadata(entity.getAttributes());

                    if (runInstance != null) {
                        response.setProgress(runInstance.getProgress());
                        response.setPhase(runInstance.getPhase());
                        response.setStartedAt(runInstance.getStartedAt());
                        response.setCompletedAt(runInstance.getCompletedAt());
                        response.setErrorDetail(runInstance.getErrorDetail());
                    }

                    return ApiResponse.success(response);
                }));
    }

    @PostMapping("/batch")
    public Mono<ApiResponse<Map<String, Object>>> batchOperations(
            @Valid @RequestBody ResourceBatchRequest request) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    String batchId = "batch_" + UUID.randomUUID().toString().substring(0, 8);
                    List<Map<String, Object>> results = new ArrayList<>();

                    for (ResourceBatchRequest.BatchOperation operation : request.getOperations()) {
                        Map<String, Object> result = new HashMap<>();
                        result.put("id", operation.getId());
                        result.put("action", operation.getAction());

                        try {
                            executeOperation(operation, traceId);
                            result.put("success", true);
                            result.put("status", "completed");
                        } catch (Exception e) {
                            result.put("success", false);
                            result.put("status", "failed");
                            result.put("error", e.getMessage());
                            log.error("Batch operation failed: {} on {}", operation.getAction(),
                                    operation.getId(), e);
                        }

                        results.add(result);
                    }

                    emitEvent("resource.batch_completed", batchId, "batch",
                            Map.of("operationCount", request.getOperations().size(),
                                    "successCount", results.stream()
                                            .filter(r -> Boolean.TRUE.equals(r.get("success")))
                                            .count()), traceId);

                    Map<String, Object> data = new HashMap<>();
                    data.put("batchId", batchId);
                    data.put("results", results);

                    return ApiResponse.success(data);
                }));
    }

    @GetMapping("/{id}")
    public Mono<ApiResponse<CoreEntity>> getResource(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<CoreEntity> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(CoreEntity::getEntityId, id);
            CoreEntity entity = coreEntityMapper.selectOne(wrapper);

            if (entity == null) {
                throw BusinessException.notFound("资源不存在: " + id);
            }
            return ApiResponse.success(entity);
        });
    }

    @GetMapping
    public Mono<ApiResponse<Map<String, Object>>> listResources(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String labelKey,
            @RequestParam(required = false) String labelValue,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Mono.fromCallable(() -> {
            LambdaQueryWrapper<CoreEntity> wrapper = new LambdaQueryWrapper<>();
            if (type != null && !type.isEmpty()) {
                wrapper.eq(CoreEntity::getType, type);
            }
            if (status != null && !status.isEmpty()) {
                wrapper.eq(CoreEntity::getStatus, status);
            }
            wrapper.orderByDesc(CoreEntity::getCreatedAt);

            List<CoreEntity> entities = coreEntityMapper.selectList(wrapper);

            if (labelKey != null && !labelKey.isEmpty()) {
                entities = entities.stream()
                        .filter(e -> e.getAttributes() != null
                                && e.getAttributes().containsKey("labels")
                                && e.getAttributes().get("labels") instanceof Map
                                && ((Map<?, ?>) e.getAttributes().get("labels"))
                                .get(labelKey) != null
                                && (labelValue == null
                                || labelValue.equals(((Map<?, ?>) e.getAttributes()
                                .get("labels")).get(labelKey))))
                        .toList();
            }

            long total = entities.size();
            int fromIndex = (pageNum - 1) * pageSize;
            int toIndex = Math.min(fromIndex + pageSize, entities.size());
            List<CoreEntity> pageData = fromIndex < total ?
                    entities.subList(fromIndex, toIndex) : List.of();

            Map<String, Object> result = new HashMap<>();
            result.put("records", pageData);
            result.put("total", total);
            result.put("pageNum", pageNum);
            result.put("pageSize", pageSize);
            result.put("totalPages", (total + pageSize - 1) / pageSize);

            return ApiResponse.success(result);
        });
    }

    @DeleteMapping("/{id}")
    public Mono<ApiResponse<Void>> deleteResource(@PathVariable String id) {
        return TraceContext.getTraceId()
                .flatMap(traceId -> Mono.fromCallable(() -> {
                    LambdaQueryWrapper<CoreEntity> wrapper = new LambdaQueryWrapper<>();
                    wrapper.eq(CoreEntity::getEntityId, id);
                    CoreEntity entity = coreEntityMapper.selectOne(wrapper);

                    if (entity == null) {
                        throw BusinessException.notFound("资源不存在: " + id);
                    }

                    coreEntityMapper.delete(wrapper);

                    emitEvent("resource.deleted", id, "resource",
                            Map.of("type", entity.getType()), traceId);

                    log.info("Deleted resource: {} of type {}", id, entity.getType());
                    return ApiResponse.success(null);
                }));
    }

    private void executeOperation(ResourceBatchRequest.BatchOperation operation, String traceId) {
        String id = operation.getId();
        String action = operation.getAction();

        LambdaQueryWrapper<CoreEntity> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(CoreEntity::getEntityId, id);
        CoreEntity entity = coreEntityMapper.selectOne(wrapper);

        if (entity == null) {
            throw BusinessException.notFound("资源不存在: " + id);
        }

        LambdaQueryWrapper<RunInstance> runWrapper = new LambdaQueryWrapper<>();
        runWrapper.eq(RunInstance::getEntityId, id);
        runWrapper.orderByDesc(RunInstance::getCreatedAt);
        runWrapper.last("LIMIT 1");
        RunInstance runInstance = runInstanceMapper.selectOne(runWrapper);

        switch (action.toLowerCase()) {
            case "restart":
                entity.setStatus("provisioning");
                coreEntityMapper.updateById(entity);

                if (runInstance != null) {
                    runInstance.setPhase("restarting");
                    runInstance.setProgress(BigDecimal.ZERO);
                    runInstance.setErrorDetail(null);
                    runInstance.setCompletedAt(null);
                    runInstance.setStartedAt(LocalDateTime.now());
                    runInstanceMapper.updateById(runInstance);
                }

                emitEvent("resource.restarted", id, "resource",
                        Map.of("type", entity.getType()), traceId);
                log.info("Restarted resource: {}", id);
                break;

            case "cancel":
                entity.setStatus("cancelled");
                coreEntityMapper.updateById(entity);

                if (runInstance != null) {
                    runInstance.setPhase("cancelled");
                    runInstance.setProgress(BigDecimal.ZERO);
                    runInstance.setCompletedAt(LocalDateTime.now());
                    runInstance.setErrorDetail("用户取消操作");
                    runInstanceMapper.updateById(runInstance);
                }

                emitEvent("resource.cancelled", id, "resource",
                        Map.of("type", entity.getType()), traceId);
                log.info("Cancelled resource: {}", id);
                break;

            case "pause":
                entity.setStatus("paused");
                coreEntityMapper.updateById(entity);

                if (runInstance != null) {
                    runInstance.setPhase("paused");
                    runInstanceMapper.updateById(runInstance);
                }

                emitEvent("resource.paused", id, "resource",
                        Map.of("type", entity.getType()), traceId);
                log.info("Paused resource: {}", id);
                break;

            case "resume":
                entity.setStatus("active");
                coreEntityMapper.updateById(entity);

                if (runInstance != null) {
                    runInstance.setPhase("running");
                    runInstanceMapper.updateById(runInstance);
                }

                emitEvent("resource.resumed", id, "resource",
                        Map.of("type", entity.getType()), traceId);
                log.info("Resumed resource: {}", id);
                break;

            default:
                throw BusinessException.validationError("不支持的操作类型: " + action);
        }
    }

    private void emitEvent(String eventType, String aggregateId, String aggregateType,
                           Object payload, String traceId) {
        DomainEvent event = new DomainEvent();
        event.setEventId("evt-" + UUID.randomUUID().toString().substring(0, 8));
        event.setEventType(eventType);
        event.setAggregateId(aggregateId);
        event.setAggregateType(aggregateType);
        event.setPayload(Map.of("data", payload));
        event.setTimestamp(LocalDateTime.now());
        event.setTraceId(traceId);
        eventSink.tryEmitNext(event);
    }
}
