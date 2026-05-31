package com.datastandard.modules.logging.controller;

import com.datastandard.common.dto.ApiResponse;
import com.datastandard.common.dto.BatchOperationRequest;
import com.datastandard.common.dto.BatchOperationResult;
import com.datastandard.modules.logging.LogLevelManagementService;
import com.datastandard.modules.logging.dto.LogLevelChangeRequest;
import com.datastandard.modules.logging.dto.LogQueryRequest;
import com.datastandard.modules.logging.dto.LoggerStatusResponse;
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
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/logging")
@RequiredArgsConstructor
@Validated
@Tag(name = "日志管理", description = "日志级别和日志查询API")
public class LoggingController {

    private final LogLevelManagementService logLevelManagementService;

    @PostMapping("/level")
    @Operation(summary = "修改日志级别", description = "修改指定包路径的日志级别")
    @PreAuthorize("hasAuthority('logging:level:update')")
    public Mono<ResponseEntity<ApiResponse<LoggerStatusResponse>>> changeLogLevel(
            @Valid @RequestBody LogLevelChangeRequest request) {
        log.info("修改日志级别: packagePath={}, level={}", request.getPackagePath(), request.getLevel());
        return logLevelManagementService.changeLogLevel(request)
                .map(response -> ResponseEntity.ok(ApiResponse.success("日志级别修改成功", response)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.BAD_REQUEST)
                        .body(ApiResponse.error("LOG_LEVEL_CHANGE_FAILED", e.getMessage()))));
    }

    @GetMapping("/level/{packagePath}")
    @Operation(summary = "查询日志级别", description = "查询指定包路径的日志级别")
    @PreAuthorize("hasAuthority('logging:level:read')")
    public Mono<ResponseEntity<ApiResponse<LoggerStatusResponse>>> getLoggerStatus(
            @Parameter(description = "包路径") @PathVariable @NotBlank(message = "包路径不能为空") String packagePath) {
        log.info("查询日志级别: packagePath={}", packagePath);
        return logLevelManagementService.getLoggerStatus(packagePath)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("LOG_LEVEL_QUERY_FAILED", e.getMessage()))));
    }

    @GetMapping("/levels")
    @Operation(summary = "查询所有日志级别", description = "查询所有日志记录器的级别配置")
    @PreAuthorize("hasAuthority('logging:level:read')")
    public Mono<ResponseEntity<ApiResponse<LoggerStatusResponse.BatchResponse>>> getAllLoggerStatuses(
            @Parameter(description = "过滤条件") @RequestParam(required = false) String filter) {
        log.info("查询所有日志级别: filter={}", filter);
        return logLevelManagementService.getAllLoggerStatusesBatch(filter)
                .map(response -> ResponseEntity.ok(ApiResponse.success(response)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("LOG_LEVELS_QUERY_FAILED", e.getMessage()))));
    }

    @DeleteMapping("/level/{packagePath}")
    @Operation(summary = "重置日志级别", description = "重置指定包路径的日志级别到默认值")
    @PreAuthorize("hasAuthority('logging:level:delete')")
    public Mono<ResponseEntity<ApiResponse<Boolean>>> resetLogLevel(
            @Parameter(description = "包路径") @PathVariable @NotBlank(message = "包路径不能为空") String packagePath) {
        log.info("重置日志级别: packagePath={}", packagePath);
        return logLevelManagementService.resetLogLevel(packagePath)
                .map(success -> ResponseEntity.ok(ApiResponse.success("日志级别重置成功", success)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("LOG_LEVEL_RESET_FAILED", e.getMessage()))));
    }

    @PostMapping("/levels/reset")
    @Operation(summary = "重置所有日志级别", description = "重置所有非持久化的日志级别配置")
    @PreAuthorize("hasAuthority('logging:level:delete')")
    public Mono<ResponseEntity<ApiResponse<Boolean>>> resetAllLogLevels() {
        log.info("重置所有日志级别");
        return logLevelManagementService.resetAllLogLevels()
                .map(success -> ResponseEntity.ok(ApiResponse.success("所有日志级别重置成功", success)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("LOG_LEVELS_RESET_FAILED", e.getMessage()))));
    }

    @PostMapping("/query")
    @Operation(summary = "查询日志", description = "根据条件查询日志记录")
    @PreAuthorize("hasAuthority('logging:query')")
    public Mono<ResponseEntity<ApiResponse<List<Map<String, Object>>>>> queryLogs(
            @Valid @RequestBody LogQueryRequest request) {
        log.info("查询日志: level={}, keyword={}", request.getLevel(), request.getKeyword());
        return logLevelManagementService.queryLogs(request)
                .collectList()
                .map(logs -> ResponseEntity.ok(ApiResponse.success(logs)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("LOG_QUERY_FAILED", e.getMessage()))));
    }

    @PostMapping("/batch")
    @Operation(summary = "批量操作", description = "批量执行日志操作")
    @PreAuthorize("hasAuthority('logging:batch')")
    public Mono<ResponseEntity<ApiResponse<BatchOperationResult>>> batchOperation(
            @Valid @RequestBody BatchOperationRequest<String> request) {
        log.info("批量操作日志: operationType={}", request.getOperationType());

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
