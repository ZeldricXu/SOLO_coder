package com.chaoslab.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.chaoslab.common.ApiResponse;
import com.chaoslab.common.PageResult;
import com.chaoslab.entity.CoreEntity;
import com.chaoslab.entity.RunInstance;
import com.chaoslab.exception.BusinessException;
import com.chaoslab.mapper.CoreEntityMapper;
import com.chaoslab.mapper.RunInstanceMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
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

    @PostMapping
    public Mono<ApiResponse<Map<String, Object>>> createResource(
            @Valid @RequestBody ResourceCreateRequest request) {
        return Mono.fromCallable(() -> {
            CoreEntity entity = new CoreEntity();
            entity.setEntId("rsc_" + UUID.randomUUID().toString().substring(0, 6));
            entity.setType(request.getType());
            entity.setStatus("provisioning");
            entity.setAttributes(request.getConfig());

            if (request.getLabels() != null && !request.getLabels().isEmpty()) {
                entity.getAttributes().put("labels", request.getLabels());
            }

            coreEntityMapper.insert(entity);

            RunInstance runInstance = new RunInstance();
            runInstance.setRunId("run_" + UUID.randomUUID().toString().substring(0, 6));
            runInstance.setEntityId(entity.getEntId());
            runInstance.setPhase("initializing");
            runInstance.setProgress(java.math.BigDecimal.ZERO);
            runInstance.setStartedAt(LocalDateTime.now());

            runInstanceMapper.insert(runInstance);

            log.info("Created resource: {} type: {}", entity.getEntId(), request.getType());

            Map<String, Object> result = Map.of(
                    "id", entity.getEntId(),
                    "status", "provisioning"
            );
            return ApiResponse.success(result);
        });
    }

    @GetMapping("/{id}/status")
    public Mono<ApiResponse<Map<String, Object>>> getResourceStatus(@PathVariable String id) {
        return Mono.fromCallable(() -> {
            CoreEntity entity = coreEntityMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CoreEntity>()
                            .eq(CoreEntity::getEntId, id));
            if (entity == null) {
                throw BusinessException.notFound("资源不存在: " + id);
            }

            RunInstance runInstance = runInstanceMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<RunInstance>()
                            .eq(RunInstance::getEntityId, id)
                            .orderByDesc(RunInstance::getCreatedAt)
                            .last("LIMIT 1"));

            Map<String, Object> result = new java.util.HashMap<>();
            result.put("id", entity.getEntId());
            result.put("status", entity.getStatus());
            result.put("type", entity.getType());
            if (runInstance != null) {
                result.put("phase", runInstance.getPhase());
                result.put("progress", runInstance.getProgress());
            }

            return ApiResponse.success(result);
        });
    }

    @PostMapping("/batch")
    public Mono<ApiResponse<Map<String, Object>>> batchOperations(
            @Valid @RequestBody BatchOperationRequest request) {
        return Mono.fromCallable(() -> {
            String batchId = "batch_" + UUID.randomUUID().toString().substring(0, 6);
            log.info("Processing batch operation: {} with {} operations", batchId, request.getOperations().size());

            List<Map<String, Object>> results = request.getOperations().stream()
                    .map(op -> processSingleOperation(op))
                    .toList();

            Map<String, Object> result = Map.of(
                    "batchId", batchId,
                    "results", results
            );
            return ApiResponse.success(result);
        });
    }

    @GetMapping
    public Mono<ApiResponse<PageResult<CoreEntity>>> listResources(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize) {
        return Mono.fromCallable(() -> {
            var wrapper = new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CoreEntity>();
            if (type != null && !type.isEmpty()) {
                wrapper.eq(CoreEntity::getType, type);
            }
            if (status != null && !status.isEmpty()) {
                wrapper.eq(CoreEntity::getStatus, status);
            }
            wrapper.orderByDesc(CoreEntity::getCreatedAt);

            Page<CoreEntity> page = coreEntityMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
            return ApiResponse.success(new PageResult<>(
                    page.getRecords(),
                    page.getTotal(),
                    page.getCurrent(),
                    page.getSize()
            ));
        });
    }

    private Map<String, Object> processSingleOperation(Map<String, Object> op) {
        Map<String, Object> result = new java.util.HashMap<>();
        try {
            String action = (String) op.get("action");
            String id = (String) op.get("id");

            CoreEntity entity = coreEntityMapper.selectOne(
                    new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<CoreEntity>()
                            .eq(CoreEntity::getEntId, id));
            if (entity == null) {
                result.put("id", id);
                result.put("success", false);
                result.put("error", "Resource not found");
                return result;
            }

            switch (action) {
                case "start" -> entity.setStatus("running");
                case "stop" -> entity.setStatus("stopped");
                case "restart" -> entity.setStatus("restarting");
                case "delete" -> {
                    entity.setStatus("deleted");
                    coreEntityMapper.deleteById(entity.getId());
                }
                default -> {
                    result.put("id", id);
                    result.put("success", false);
                    result.put("error", "Unknown action: " + action);
                    return result;
                }
            }
            coreEntityMapper.updateById(entity);

            result.put("id", id);
            result.put("success", true);
            result.put("newStatus", entity.getStatus());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getMessage());
        }
        return result;
    }

    @Data
    public static class ResourceCreateRequest {
        @NotBlank(message = "资源类型不能为空")
        private String type;

        @NotNull(message = "配置不能为空")
        private Map<String, Object> config;

        private Map<String, String> labels;
    }

    @Data
    public static class BatchOperationRequest {
        @NotNull(message = "操作列表不能为空")
        private List<Map<String, Object>> operations;
    }
}
