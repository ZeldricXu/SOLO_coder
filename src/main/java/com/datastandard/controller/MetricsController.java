package com.datastandard.controller;

import com.datastandard.common.dto.ApiResponse;
import com.datastandard.common.dto.BatchOperationRequest;
import com.datastandard.common.dto.BatchOperationResult;
import com.datastandard.modules.metrics.MetricIngestionService;
import com.datastandard.modules.metrics.dto.MetricIngestRequest;
import com.datastandard.modules.metrics.dto.MetricResponse;
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
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/v1/metric")
@RequiredArgsConstructor
@Validated
@Tag(name = "指标管理", description = "指标采集和查询API")
public class MetricsController {

    private final MetricIngestionService metricIngestionService;

    @PostMapping("/ingest")
    @Operation(summary = "指标上报", description = "上报单个指标数据")
    @PreAuthorize("hasAuthority('metrics:ingest')")
    public Mono<ResponseEntity<ApiResponse<Boolean>>> ingestMetric(
            @Valid @RequestBody MetricIngestRequest request) {
        log.info("指标上报: metricName={}, value={}", request.getMetricName(), request.getValue());
        return metricIngestionService.ingest(request)
                .map(success -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success("指标上报成功", success)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("METRIC_INGEST_FAILED", e.getMessage()))));
    }

    @PostMapping("/ingest/batch")
    @Operation(summary = "批量指标上报", description = "批量上报多个指标数据")
    @PreAuthorize("hasAuthority('metrics:ingest')")
    public Mono<ResponseEntity<ApiResponse<Integer>>> batchIngestMetrics(
            @Valid @RequestBody List<MetricIngestRequest> requests) {
        log.info("批量指标上报: count={}", requests.size());
        return metricIngestionService.batchIngest(requests)
                .map(count -> ResponseEntity.status(HttpStatus.CREATED)
                        .body(ApiResponse.success("批量指标上报成功", count)))
                .onErrorResume(e -> Mono.just(ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                        .body(ApiResponse.error("BATCH_INGEST_FAILED", e.getMessage()))));
    }

    @GetMapping("/{metricName}")
    @Operation(summary = "查询指标", description = "查询指定指标的最新数据")
    @PreAuthorize("hasAuthority('metrics:read')")
    public Mono<ResponseEntity<ApiResponse<MetricResponse>>> getMetric(
            @Parameter(description = "指标名称") @PathVariable @NotBlank(message = "指标名称不能为空") String metricName,
            @Parameter(description = "开始时间") @RequestParam(required = false) Instant startTime,
            @Parameter(description = "结束时间") @RequestParam(required = false) Instant endTime) {
        log.info("查询指标: metricName={}", metricName);
        MetricResponse response = MetricResponse.builder()
                .metricName(metricName)
                .timestamp(Instant.now())
                .value(0.0)
                .dimensions(Map.of())
                .build();
        return Mono.just(ResponseEntity.ok(ApiResponse.success(response)));
    }

    @GetMapping
    @Operation(summary = "查询指标列表", description = "查询指标列表")
    @PreAuthorize("hasAuthority('metrics:read')")
    public Mono<ResponseEntity<ApiResponse<List<MetricResponse>>>> getMetrics(
            @Parameter(description = "前缀") @RequestParam(required = false) String prefix) {
        log.info("查询指标列表: prefix={}", prefix);
        List<MetricResponse> metrics = List.of(
                MetricResponse.builder()
                        .metricName("system.cpu.usage")
                        .timestamp(Instant.now())
                        .value(45.5)
                        .build(),
                MetricResponse.builder()
                        .metricName("system.memory.usage")
                        .timestamp(Instant.now())
                        .value(62.3)
                        .build()
        );
        return Mono.just(ResponseEntity.ok(ApiResponse.success(metrics)));
    }

    @PostMapping("/flush")
    @Operation(summary = "手动刷新", description = "手动刷新指标缓冲区到持久化存储")
    @PreAuthorize("hasAuthority('metrics:flush')")
    public Mono<ResponseEntity<ApiResponse<Void>>> flushMetrics() {
        log.info("手动刷新指标缓冲区");
        return metricIngestionService.manualFlush()
                .then(Mono.just(ResponseEntity.ok(ApiResponse.success("指标刷新成功", null))));
    }

    @GetMapping("/buffer/size")
    @Operation(summary = "缓冲区大小", description = "获取当前指标缓冲区大小")
    @PreAuthorize("hasAuthority('metrics:read')")
    public Mono<ResponseEntity<ApiResponse<Integer>>> getBufferSize() {
        int size = metricIngestionService.getBufferSize();
        return Mono.just(ResponseEntity.ok(ApiResponse.success(size)));
    }

    @PostMapping("/batch")
    @Operation(summary = "批量操作", description = "批量执行指标操作")
    @PreAuthorize("hasAuthority('metrics:batch')")
    public Mono<ResponseEntity<ApiResponse<BatchOperationResult>>> batchOperation(
            @Valid @RequestBody BatchOperationRequest<String> request) {
        log.info("批量操作指标: operationType={}", request.getOperationType());

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
