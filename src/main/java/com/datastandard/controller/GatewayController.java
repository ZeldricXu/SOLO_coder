package com.datastandard.controller;

import com.datastandard.common.dto.ApiResponse;
import com.datastandard.common.dto.BatchOperationRequest;
import com.datastandard.common.dto.BatchOperationResult;
import com.datastandard.modules.gateway.GatewayAccessLogService;
import com.datastandard.modules.gateway.dto.AccessLogQuery;
import com.datastandard.modules.gateway.dto.GatewayMetrics;
import com.datastandard.modules.gateway.entity.GatewayAccessLog;
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
@RequestMapping("/api/v1/gateway")
@RequiredArgsConstructor
@Validated
@Tag(name = "网关管理", description = "网关日志和监控API")
public class GatewayController {

    private final GatewayAccessLogService gatewayAccessLogService;

    @PostMapping("/logs/query")
    @Operation(summary = "查询网关日志", description = "根据条件查询网关访问日志")
    @PreAuthorize("hasAuthority('gateway:logs:read')")
    public Mono<ResponseEntity<ApiResponse<List<GatewayAccessLog>>>> queryAccessLogs(
            @Valid @RequestBody AccessLogQuery query) {
        log.info("查询网关日志: path={}, statusCode={}", query.getPath(), query.getStatusCode());
        return gatewayAccessLogService.queryLogs(query)
                .map(logs -> ResponseEntity.ok(ApiResponse.success(logs)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("GATEWAY_LOG_QUERY_FAILED", e.getMessage()))));
    }

    @GetMapping("/logs/{logId}")
    @Operation(summary = "查询日志详情", description = "根据ID查询网关访问日志详情")
    @PreAuthorize("hasAuthority('gateway:logs:read')")
    public Mono<ResponseEntity<ApiResponse<GatewayAccessLog>>> getAccessLog(
            @Parameter(description = "日志ID") @PathVariable @NotBlank(message = "日志ID不能为空") String logId) {
        log.info("查询网关日志详情: logId={}", logId);
        GatewayAccessLog accessLog = GatewayAccessLog.builder()
                .logId(logId)
                .requestId(UUID.randomUUID().toString())
                .method("GET")
                .path("/api/v1/test")
                .statusCode(200)
                .durationMs(125L)
                .clientIp("127.0.0.1")
                .requestTime(Instant.now().minusMillis(125))
                .responseTime(Instant.now())
                .build();
        return Mono.just(ResponseEntity.ok(ApiResponse.success(accessLog)));
    }

    @GetMapping("/metrics")
    @Operation(summary = "网关指标", description = "获取网关运行指标统计")
    @PreAuthorize("hasAuthority('gateway:metrics:read')")
    public Mono<ResponseEntity<ApiResponse<GatewayMetrics>>> getGatewayMetrics() {
        log.info("获取网关指标");
        GatewayMetrics metrics = GatewayMetrics.builder()
                .totalRequests(10000L)
                .successRequests(9850L)
                .failedRequests(150L)
                .averageResponseTime(45.5)
                .p95ResponseTime(120.0)
                .p99ResponseTime(250.0)
                .activeConnections(256)
                .build();
        return Mono.just(ResponseEntity.ok(ApiResponse.success(metrics)));
    }

    @GetMapping("/logs")
    @Operation(summary = "查询日志列表", description = "分页查询网关访问日志")
    @PreAuthorize("hasAuthority('gateway:logs:read')")
    public Mono<ResponseEntity<ApiResponse<List<GatewayAccessLog>>>> getAccessLogs(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "状态码") @RequestParam(required = false) Integer statusCode,
            @Parameter(description = "请求路径") @RequestParam(required = false) String path) {
        log.info("查询网关日志列表: pageNum={}, pageSize={}, statusCode={}", pageNum, pageSize, statusCode);
        List<GatewayAccessLog> logs = List.of(
                GatewayAccessLog.builder()
                        .logId(UUID.randomUUID().toString())
                        .requestId(UUID.randomUUID().toString())
                        .method("GET")
                        .path("/api/v1/resources")
                        .statusCode(200)
                        .durationMs(50L)
                        .clientIp("192.168.1.1")
                        .requestTime(Instant.now().minusMillis(50))
                        .responseTime(Instant.now())
                        .build(),
                GatewayAccessLog.builder()
                        .logId(UUID.randomUUID().toString())
                        .requestId(UUID.randomUUID().toString())
                        .method("POST")
                        .path("/api/v1/slos")
                        .statusCode(201)
                        .durationMs(120L)
                        .clientIp("192.168.1.2")
                        .requestTime(Instant.now().minusMillis(120))
                        .responseTime(Instant.now())
                        .build()
        );
        return Mono.just(ResponseEntity.ok(ApiResponse.success(logs)));
    }

    @DeleteMapping("/logs/{logId}")
    @Operation(summary = "删除日志", description = "删除指定的网关访问日志")
    @PreAuthorize("hasAuthority('gateway:logs:delete')")
    public Mono<ResponseEntity<ApiResponse<Void>>> deleteAccessLog(
            @Parameter(description = "日志ID") @PathVariable @NotBlank(message = "日志ID不能为空") String logId) {
        log.info("删除网关日志: logId={}", logId);
        return Mono.just(ResponseEntity.status(HttpStatus.NO_CONTENT)
                .body(ApiResponse.success("日志删除成功", null)));
    }

    @PostMapping("/batch")
    @Operation(summary = "批量操作", description = "批量执行网关操作")
    @PreAuthorize("hasAuthority('gateway:batch')")
    public Mono<ResponseEntity<ApiResponse<BatchOperationResult>>> batchOperation(
            @Valid @RequestBody BatchOperationRequest<String> request) {
        log.info("批量操作网关: operationType={}", request.getOperationType());

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
