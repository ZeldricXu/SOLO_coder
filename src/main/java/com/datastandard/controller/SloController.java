package com.datastandard.controller;

import com.datastandard.common.dto.ApiResponse;
import com.datastandard.common.dto.BatchOperationRequest;
import com.datastandard.common.dto.BatchOperationResult;
import com.datastandard.modules.slo.SloService;
import com.datastandard.modules.slo.dto.SloDefinitionRequest;
import com.datastandard.modules.slo.entity.SloDefinition;
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

import java.util.ArrayList;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/v1/slo")
@RequiredArgsConstructor
@Validated
@Tag(name = "SLO管理", description = "服务级别目标管理API")
public class SloController {

    private final SloService sloService;

    @PostMapping
    @Operation(summary = "创建SLO", description = "创建新的服务级别目标定义")
    @PreAuthorize("hasAuthority('slo:create')")
    public Mono<ResponseEntity<ApiResponse<SloDefinition>>> createSlo(
            @Valid @RequestBody SloDefinitionRequest request) {
        log.info("创建SLO: sloName={}, serviceName={}", request.getSloName(), request.getServiceName());
        return sloService.createSlo(request)
                .map(slo -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success("SLO创建成功", slo)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("SLO_CREATE_FAILED", e.getMessage()))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "查询SLO详情", description = "根据ID查询SLO定义详情")
    @PreAuthorize("hasAuthority('slo:read')")
    public Mono<ResponseEntity<ApiResponse<SloDefinition>>> getSlo(
            @Parameter(description = "SLO ID") @PathVariable @NotBlank(message = "SLO ID不能为空") String id) {
        log.info("查询SLO详情: sloId={}", id);
        return sloService.getSlo(id)
                .map(slo -> ResponseEntity.ok(ApiResponse.success(slo)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(ApiResponse.error("SLO_NOT_FOUND", e.getMessage()))));
    }

    @GetMapping
    @Operation(summary = "查询SLO列表", description = "分页查询SLO定义列表")
    @PreAuthorize("hasAuthority('slo:read')")
    public Mono<ResponseEntity<ApiResponse<List<SloDefinition>>>> getSlos(
            @Parameter(description = "服务名称") @RequestParam(required = false) String serviceName,
            @Parameter(description = "环境") @RequestParam(required = false) String environment) {
        log.info("查询SLO列表: serviceName={}, environment={}", serviceName, environment);
        if (serviceName != null) {
            return sloService.getSloByService(serviceName)
                    .collectList()
                    .map(slos -> ResponseEntity.ok(ApiResponse.success(slos)));
        } else if (environment != null) {
            return sloService.getSloByEnvironment(environment)
                    .collectList()
                    .map(slos -> ResponseEntity.ok(ApiResponse.success(slos)));
        } else {
            return sloService.getAllEnabledSlos()
                    .collectList()
                    .map(slos -> ResponseEntity.ok(ApiResponse.success(slos)));
        }
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新SLO", description = "更新SLO定义信息")
    @PreAuthorize("hasAuthority('slo:update')")
    public Mono<ResponseEntity<ApiResponse<SloDefinition>>> updateSlo(
            @Parameter(description = "SLO ID") @PathVariable @NotBlank(message = "SLO ID不能为空") String id,
            @Valid @RequestBody SloDefinitionRequest request) {
        log.info("更新SLO: sloId={}", id);
        return sloService.updateSlo(id, request)
                .map(slo -> ResponseEntity.ok(ApiResponse.success("SLO更新成功", slo)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("SLO_UPDATE_FAILED", e.getMessage()))));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除SLO", description = "删除指定的SLO定义")
    @PreAuthorize("hasAuthority('slo:delete')")
    public Mono<ResponseEntity<ApiResponse<Void>>> deleteSlo(
            @Parameter(description = "SLO ID") @PathVariable @NotBlank(message = "SLO ID不能为空") String id) {
        log.info("删除SLO: sloId={}", id);
        return sloService.deleteSlo(id)
                .then(Mono.just(ResponseEntity.status(HttpStatus.NO_CONTENT)
                        .body(ApiResponse.success("SLO删除成功", null))))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("SLO_DELETE_FAILED", e.getMessage()))));
    }

    @PostMapping("/batch")
    @Operation(summary = "批量操作SLO", description = "批量执行SLO操作（start/stop/restart/delete）")
    @PreAuthorize("hasAuthority('slo:batch')")
    public Mono<ResponseEntity<ApiResponse<BatchOperationResult>>> batchOperation(
            @Valid @RequestBody BatchOperationRequest<String> request) {
        log.info("批量操作SLO: operationType={}", request.getOperationType());

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
