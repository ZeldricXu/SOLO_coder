package com.datastandard.controller;

import com.datastandard.common.dto.ApiResponse;
import com.datastandard.common.dto.BatchOperationRequest;
import com.datastandard.common.dto.BatchOperationResult;
import com.datastandard.common.dto.ResourceRequest;
import com.datastandard.common.dto.ResourceStatus;
import com.datastandard.common.util.TraceContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/resources")
@RequiredArgsConstructor
@Validated
@Tag(name = "资源管理", description = "资源管理相关API")
public class ResourceController {

    @PostMapping
    @Operation(summary = "创建资源", description = "创建新的资源实例")
    @PreAuthorize("hasAuthority('resource:create')")
    public Mono<ResponseEntity<ApiResponse<ResourceRequest>>> createResource(
            @Valid @RequestBody ResourceRequest request) {
        log.info("创建资源: type={}", request.getType());
        String resourceId = "rsc_" + cn.hutool.core.util.IdUtil.nanoId(6);
        request.setId(resourceId);
        request.setStatus("provisioning");
        return Mono.just(ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(201, "资源创建成功", request)));
    }

    @GetMapping("/{id}/status")
    @Operation(summary = "查询资源状态", description = "根据资源ID查询资源运行状态")
    @PreAuthorize("hasAuthority('resource:read')")
    public Mono<ResponseEntity<ApiResponse<ResourceStatus>>> getResourceStatus(
            @Parameter(description = "资源ID") @PathVariable @NotBlank(message = "资源ID不能为空") String id) {
        log.info("查询资源状态: resourceId={}", id);
        ResourceStatus status = ResourceStatus.builder()
                .id(id)
                .status("completed")
                .progress(new BigDecimal("0.8"))
                .message("资源运行正常")
                .startTime(LocalDateTime.now().minusHours(1))
                .endTime(null)
                .durationMs(3600000L)
                .build();
        return Mono.just(ResponseEntity.ok(ApiResponse.success(status)));
    }

    @PostMapping("/batch")
    @Operation(summary = "批量操作", description = "批量执行资源操作（start/stop/restart/delete）")
    @PreAuthorize("hasAuthority('resource:batch')")
    public Mono<ResponseEntity<ApiResponse<BatchOperationResult>>> batchOperation(
            @Valid @RequestBody BatchOperationRequest<ResourceRequest> request) {
        log.info("批量操作资源: operationType={}, count={}", request.getOperationType(),
                request.getIds() != null ? request.getIds().size() : 0);

        String operationType = request.getOperationType();
        List<String> validOperations = List.of("start", "stop", "restart", "delete");
        if (!validOperations.contains(operationType.toLowerCase())) {
            return Mono.just(ResponseEntity.badRequest()
                    .body(ApiResponse.error("INVALID_OPERATION", "无效的操作类型，支持: start/stop/restart/delete")));
        }

        List<String> successIds = new ArrayList<>();
        List<BatchOperationResult.FailedItem> failedItems = new ArrayList<>();

        if (request.getIds() != null) {
            for (String id : request.getIds()) {
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

        HttpStatus status = failedItems.isEmpty() ? HttpStatus.OK : HttpStatus.PARTIAL_CONTENT;
        return Mono.just(ResponseEntity.status(status)
                .body(ApiResponse.success("批量操作完成", result)));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询资源详情", description = "根据ID查询资源详细信息")
    @PreAuthorize("hasAuthority('resource:read')")
    public Mono<ResponseEntity<ApiResponse<ResourceRequest>>> getResource(
            @Parameter(description = "资源ID") @PathVariable @NotBlank(message = "资源ID不能为空") String id) {
        log.info("查询资源详情: resourceId={}", id);
        ResourceRequest resource = ResourceRequest.builder()
                .id(id)
                .type("job")
                .config(Map.of("timeout", 30, "retries", 3))
                .labels(Map.of("env", "prod", "team", "data"))
                .status("ACTIVE")
                .build();
        return Mono.just(ResponseEntity.ok(ApiResponse.success(resource)));
    }

    @GetMapping
    @Operation(summary = "查询资源列表", description = "分页查询资源列表")
    @PreAuthorize("hasAuthority('resource:read')")
    public Mono<ResponseEntity<ApiResponse<List<ResourceRequest>>>> getResources(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "关键词") @RequestParam(required = false) String keyword) {
        log.info("查询资源列表: pageNum={}, pageSize={}, keyword={}", pageNum, pageSize, keyword);
        List<ResourceRequest> resources = List.of(
                ResourceRequest.builder()
                        .id("rsc_" + UUID.randomUUID().toString().substring(0, 6))
                        .type("SERVICE")
                        .labels(Map.of("env", "prod"))
                        .status("ACTIVE")
                        .build(),
                ResourceRequest.builder()
                        .id("rsc_" + UUID.randomUUID().toString().substring(0, 6))
                        .type("DATABASE")
                        .labels(Map.of("env", "dev"))
                        .status("ACTIVE")
                        .build()
        );
        return Mono.just(ResponseEntity.ok(ApiResponse.success(resources)));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新资源", description = "更新资源信息")
    @PreAuthorize("hasAuthority('resource:update')")
    public Mono<ResponseEntity<ApiResponse<ResourceRequest>>> updateResource(
            @Parameter(description = "资源ID") @PathVariable @NotBlank(message = "资源ID不能为空") String id,
            @Valid @RequestBody ResourceRequest request) {
        log.info("更新资源: resourceId={}", id);
        request.setId(id);
        return Mono.just(ResponseEntity.ok(ApiResponse.success("资源更新成功", request)));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除资源", description = "删除指定资源")
    @PreAuthorize("hasAuthority('resource:delete')")
    public Mono<ResponseEntity<ApiResponse<Void>>> deleteResource(
            @Parameter(description = "资源ID") @PathVariable @NotBlank(message = "资源ID不能为空") String id) {
        log.info("删除资源: resourceId={}", id);
        return Mono.just(ResponseEntity.status(HttpStatus.NO_CONTENT)
                .body(ApiResponse.success("资源删除成功", null)));
    }
}
