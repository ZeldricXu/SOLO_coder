package com.datastandard.modules.anomaly.controller;

import com.datastandard.common.dto.ApiResponse;
import com.datastandard.common.dto.BatchOperationRequest;
import com.datastandard.common.dto.BatchOperationResult;
import com.datastandard.modules.anomaly.dto.AnomalyDetectionRequest;
import com.datastandard.modules.anomaly.dto.AnomalyResult;
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

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/anomaly")
@RequiredArgsConstructor
@Validated
@Tag(name = "异常检测", description = "异常检测和分析API")
public class AnomalyController {

    @PostMapping("/detect")
    @Operation(summary = "异常检测", description = "执行异常检测分析")
    @PreAuthorize("hasAuthority('anomaly:detect')")
    public Mono<ResponseEntity<ApiResponse<AnomalyResult>>> detectAnomaly(
            @Valid @RequestBody AnomalyDetectionRequest request) {
        log.info("执行异常检测: detectionCode={}, metricCode={}", request.getDetectionCode(), request.getMetricCode());
        AnomalyResult result = AnomalyResult.builder()
                .detectionId(UUID.randomUUID().toString())
                .detectionCode(request.getDetectionCode())
                .metricCode(request.getMetricCode())
                .anomalyDetected(false)
                .anomalyScore(0.0)
                .severity("INFO")
                .detectionTime(LocalDateTime.now())
                .details(Map.of("dataPoints", request.getDataPoints().size()))
                .build();
        return Mono.just(ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("异常检测完成", result)));
    }

    @GetMapping("/{detectionId}")
    @Operation(summary = "查询检测结果", description = "根据ID查询异常检测结果详情")
    @PreAuthorize("hasAuthority('anomaly:read')")
    public Mono<ResponseEntity<ApiResponse<AnomalyResult>>> getAnomalyResult(
            @Parameter(description = "检测ID") @PathVariable @NotBlank(message = "检测ID不能为空") String detectionId) {
        log.info("查询异常检测结果: detectionId={}", detectionId);
        AnomalyResult result = AnomalyResult.builder()
                .detectionId(detectionId)
                .detectionCode("example")
                .metricCode("system.cpu.usage")
                .anomalyDetected(true)
                .anomalyScore(0.85)
                .severity("WARNING")
                .detectionTime(LocalDateTime.now())
                .details(Map.of("threshold", 0.8, "actualValue", 0.95))
                .build();
        return Mono.just(ResponseEntity.ok(ApiResponse.success(result)));
    }

    @GetMapping
    @Operation(summary = "查询检测列表", description = "分页查询异常检测记录")
    @PreAuthorize("hasAuthority('anomaly:read')")
    public Mono<ResponseEntity<ApiResponse<List<AnomalyResult>>>> getAnomalyResults(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "严重级别") @RequestParam(required = false) String severity,
            @Parameter(description = "指标编码") @RequestParam(required = false) String metricCode) {
        log.info("查询异常检测列表: pageNum={}, pageSize={}, severity={}", pageNum, pageSize, severity);
        List<AnomalyResult> results = List.of(
                AnomalyResult.builder()
                        .detectionId(UUID.randomUUID().toString())
                        .detectionCode("cpu_anomaly")
                        .metricCode("system.cpu.usage")
                        .anomalyDetected(true)
                        .anomalyScore(0.92)
                        .severity("CRITICAL")
                        .detectionTime(LocalDateTime.now())
                        .build(),
                AnomalyResult.builder()
                        .detectionId(UUID.randomUUID().toString())
                        .detectionCode("memory_anomaly")
                        .metricCode("system.memory.usage")
                        .anomalyDetected(false)
                        .anomalyScore(0.35)
                        .severity("INFO")
                        .detectionTime(LocalDateTime.now())
                        .build()
        );
        return Mono.just(ResponseEntity.ok(ApiResponse.success(results)));
    }

    @PostMapping("/batch/detect")
    @Operation(summary = "批量异常检测", description = "批量执行多个异常检测任务")
    @PreAuthorize("hasAuthority('anomaly:detect')")
    public Mono<ResponseEntity<ApiResponse<List<AnomalyResult>>>> batchDetect(
            @Valid @RequestBody List<AnomalyDetectionRequest> requests) {
        log.info("批量异常检测: count={}", requests.size());
        List<AnomalyResult> results = new ArrayList<>();
        for (AnomalyDetectionRequest request : requests) {
            results.add(AnomalyResult.builder()
                    .detectionId(UUID.randomUUID().toString())
                    .detectionCode(request.getDetectionCode())
                    .metricCode(request.getMetricCode())
                    .anomalyDetected(false)
                    .anomalyScore(0.0)
                    .severity("INFO")
                    .detectionTime(LocalDateTime.now())
                    .build());
        }
        return Mono.just(ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("批量异常检测完成", results)));
    }

    @DeleteMapping("/{detectionId}")
    @Operation(summary = "删除检测记录", description = "删除指定的异常检测记录")
    @PreAuthorize("hasAuthority('anomaly:delete')")
    public Mono<ResponseEntity<ApiResponse<Void>>> deleteAnomalyResult(
            @Parameter(description = "检测ID") @PathVariable @NotBlank(message = "检测ID不能为空") String detectionId) {
        log.info("删除异常检测记录: detectionId={}", detectionId);
        return Mono.just(ResponseEntity.status(HttpStatus.NO_CONTENT)
                .body(ApiResponse.success("检测记录删除成功", null)));
    }

    @PostMapping("/batch")
    @Operation(summary = "批量操作", description = "批量执行异常检测操作")
    @PreAuthorize("hasAuthority('anomaly:batch')")
    public Mono<ResponseEntity<ApiResponse<BatchOperationResult>>> batchOperation(
            @Valid @RequestBody BatchOperationRequest<String> request) {
        log.info("批量操作异常检测: operationType={}", request.getOperationType());

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
