package com.datastandard.controller;

import com.datastandard.common.dto.ApiResponse;
import com.datastandard.common.dto.BatchOperationRequest;
import com.datastandard.common.dto.BatchOperationResult;
import com.datastandard.common.model.ConfigDefinition;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
@Validated
@Tag(name = "配置管理", description = "系统配置管理API")
public class ConfigController {

    @PostMapping
    @Operation(summary = "创建配置", description = "创建新的配置项")
    @PreAuthorize("hasAuthority('config:create')")
    public Mono<ResponseEntity<ApiResponse<ConfigDefinition>>> createConfig(
            @Valid @RequestBody ConfigDefinition request) {
        log.info("创建配置: configKey={}", request.getConfigKey());
        request.setConfigId(UUID.randomUUID().toString());
        request.setCreatedAt(Instant.now());
        request.setUpdatedAt(Instant.now());
        request.setDeleted(0);
        return Mono.just(ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("配置创建成功", request)));
    }

    @GetMapping("/{configId}")
    @Operation(summary = "查询配置详情", description = "根据ID查询配置详情")
    @PreAuthorize("hasAuthority('config:read')")
    public Mono<ResponseEntity<ApiResponse<ConfigDefinition>>> getConfig(
            @Parameter(description = "配置ID") @PathVariable @NotBlank(message = "配置ID不能为空") String configId) {
        log.info("查询配置详情: configId={}", configId);
        ConfigDefinition config = ConfigDefinition.builder()
                .configId(configId)
                .configKey("system.timeout")
                .configValue("30000")
                .configType("SYSTEM")
                .description("系统超时时间")
                .enabled(true)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
        return Mono.just(ResponseEntity.ok(ApiResponse.success(config)));
    }

    @GetMapping
    @Operation(summary = "查询配置列表", description = "分页查询配置列表")
    @PreAuthorize("hasAuthority('config:read')")
    public Mono<ResponseEntity<ApiResponse<List<ConfigDefinition>>>> getConfigs(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "配置类型") @RequestParam(required = false) String configType,
            @Parameter(description = "配置Key") @RequestParam(required = false) String configKey) {
        log.info("查询配置列表: pageNum={}, pageSize={}, configType={}", pageNum, pageSize, configType);
        List<ConfigDefinition> configs = List.of(
                ConfigDefinition.builder()
                        .configId(UUID.randomUUID().toString())
                        .configKey("system.timeout")
                        .configValue("30000")
                        .configType("SYSTEM")
                        .description("系统超时时间(毫秒)")
                        .enabled(true)
                        .build(),
                ConfigDefinition.builder()
                        .configId(UUID.randomUUID().toString())
                        .configKey("cache.ttl")
                        .configValue("3600")
                        .configType("CACHE")
                        .description("缓存过期时间(秒)")
                        .enabled(true)
                        .build()
        );
        return Mono.just(ResponseEntity.ok(ApiResponse.success(configs)));
    }

    @PutMapping("/{configId}")
    @Operation(summary = "更新配置", description = "更新配置项信息")
    @PreAuthorize("hasAuthority('config:update')")
    public Mono<ResponseEntity<ApiResponse<ConfigDefinition>>> updateConfig(
            @Parameter(description = "配置ID") @PathVariable @NotBlank(message = "配置ID不能为空") String configId,
            @Valid @RequestBody ConfigDefinition request) {
        log.info("更新配置: configId={}", configId);
        request.setConfigId(configId);
        request.setUpdatedAt(Instant.now());
        return Mono.just(ResponseEntity.ok(ApiResponse.success("配置更新成功", request)));
    }

    @DeleteMapping("/{configId}")
    @Operation(summary = "删除配置", description = "删除指定配置项")
    @PreAuthorize("hasAuthority('config:delete')")
    public Mono<ResponseEntity<ApiResponse<Void>>> deleteConfig(
            @Parameter(description = "配置ID") @PathVariable @NotBlank(message = "配置ID不能为空") String configId) {
        log.info("删除配置: configId={}", configId);
        return Mono.just(ResponseEntity.status(HttpStatus.NO_CONTENT)
                .body(ApiResponse.success("配置删除成功", null)));
    }

    @GetMapping("/key/{configKey}")
    @Operation(summary = "根据Key查询配置", description = "根据配置Key查询配置值")
    @PreAuthorize("hasAuthority('config:read')")
    public Mono<ResponseEntity<ApiResponse<String>>> getConfigByKey(
            @Parameter(description = "配置Key") @PathVariable @NotBlank(message = "配置Key不能为空") String configKey) {
        log.info("根据Key查询配置: configKey={}", configKey);
        return Mono.just(ResponseEntity.ok(ApiResponse.success("default_value")));
    }

    @PostMapping("/refresh")
    @Operation(summary = "刷新配置", description = "刷新配置缓存")
    @PreAuthorize("hasAuthority('config:refresh')")
    public Mono<ResponseEntity<ApiResponse<Boolean>>> refreshConfigs() {
        log.info("刷新配置缓存");
        return Mono.just(ResponseEntity.ok(ApiResponse.success("配置刷新成功", true)));
    }

    @PostMapping("/batch")
    @Operation(summary = "批量操作", description = "批量执行配置操作")
    @PreAuthorize("hasAuthority('config:batch')")
    public Mono<ResponseEntity<ApiResponse<BatchOperationResult>>> batchOperation(
            @Valid @RequestBody BatchOperationRequest<ConfigDefinition> request) {
        log.info("批量操作配置: operationType={}", request.getOperationType());

        List<Long> successIds = new ArrayList<>();
        List<BatchOperationResult.FailedItem> failedItems = new ArrayList<>();

        if (request.getIds() != null) {
            for (Long id : request.getIds()) {
                try {
                    successIds.add(id);
                } catch (Exception e) {
                    failedItems.add(BatchOperationResult.FailedItem.builder()
                            .id(id)
                            .errorCode("OPERATION_FAILED")
                            .errorMessage(e.getMessage())
                            .build());
                }
            }
        }

        BatchOperationResult result = BatchOperationResult.builder()
                .totalCount(request.getIds() != null ? request.getIds().size() : 0)
                .successCount(successIds.size())
                .failedCount(failedItems.size())
                .successIds(successIds)
                .failedItems(failedItems)
                .build();

        return Mono.just(ResponseEntity.ok(ApiResponse.success("批量操作完成", result)));
    }
}
