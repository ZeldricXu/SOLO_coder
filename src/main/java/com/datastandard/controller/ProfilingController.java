package com.datastandard.controller;

import com.datastandard.common.dto.ApiResponse;
import com.datastandard.common.dto.BatchOperationRequest;
import com.datastandard.common.dto.BatchOperationResult;
import com.datastandard.common.model.ProfilingSession;
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
import java.util.UUID;

@Slf4j
@RestController
@RequestMapping("/api/v1/profiling")
@RequiredArgsConstructor
@Validated
@Tag(name = "性能剖析", description = "性能剖析和诊断API")
public class ProfilingController {

    @PostMapping("/session/start")
    @Operation(summary = "开始剖析", description = "启动一个新的性能剖析会话")
    @PreAuthorize("hasAuthority('profiling:start')")
    public Mono<ResponseEntity<ApiResponse<ProfilingSession>>> startProfiling(
            @Valid @RequestBody ProfilingSession request) {
        log.info("启动性能剖析: target={}, duration={}s", request.getTarget(), request.getDurationSeconds());
        request.setSessionId(UUID.randomUUID().toString());
        request.setStatus("RUNNING");
        request.setStartTime(Instant.now());
        request.setCreatedAt(Instant.now());
        request.setDeleted(0);
        return Mono.just(ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("性能剖析已启动", request)));
    }

    @PostMapping("/session/{sessionId}/stop")
    @Operation(summary = "停止剖析", description = "停止指定的性能剖析会话")
    @PreAuthorize("hasAuthority('profiling:stop')")
    public Mono<ResponseEntity<ApiResponse<ProfilingSession>>> stopProfiling(
            @Parameter(description = "会话ID") @PathVariable @NotBlank(message = "会话ID不能为空") String sessionId) {
        log.info("停止性能剖析: sessionId={}", sessionId);
        ProfilingSession session = ProfilingSession.builder()
                .sessionId(sessionId)
                .status("STOPPED")
                .endTime(Instant.now())
                .build();
        return Mono.just(ResponseEntity.ok(ApiResponse.success("性能剖析已停止", session)));
    }

    @GetMapping("/session/{sessionId}")
    @Operation(summary = "查询剖析会话", description = "根据ID查询性能剖析会话详情")
    @PreAuthorize("hasAuthority('profiling:read')")
    public Mono<ResponseEntity<ApiResponse<ProfilingSession>>> getProfilingSession(
            @Parameter(description = "会话ID") @PathVariable @NotBlank(message = "会话ID不能为空") String sessionId) {
        log.info("查询性能剖析会话: sessionId={}", sessionId);
        ProfilingSession session = ProfilingSession.builder()
                .sessionId(sessionId)
                .target("com.example.service.UserService")
                .profilingType("CPU")
                .durationSeconds(300)
                .status("COMPLETED")
                .startTime(Instant.now().minusSeconds(300))
                .endTime(Instant.now())
                .createdAt(Instant.now().minusSeconds(300))
                .build();
        return Mono.just(ResponseEntity.ok(ApiResponse.success(session)));
    }

    @GetMapping("/sessions")
    @Operation(summary = "查询剖析列表", description = "分页查询性能剖析会话列表")
    @PreAuthorize("hasAuthority('profiling:read')")
    public Mono<ResponseEntity<ApiResponse<List<ProfilingSession>>>> getProfilingSessions(
            @Parameter(description = "页码") @RequestParam(defaultValue = "1") Integer pageNum,
            @Parameter(description = "每页大小") @RequestParam(defaultValue = "10") Integer pageSize,
            @Parameter(description = "目标对象") @RequestParam(required = false) String target,
            @Parameter(description = "状态") @RequestParam(required = false) String status) {
        log.info("查询性能剖析列表: pageNum={}, pageSize={}, target={}", pageNum, pageSize, target);
        List<ProfilingSession> sessions = List.of(
                ProfilingSession.builder()
                        .sessionId(UUID.randomUUID().toString())
                        .target("com.example.service.OrderService")
                        .profilingType("CPU")
                        .status("COMPLETED")
                        .startTime(Instant.now().minusSeconds(600))
                        .endTime(Instant.now().minusSeconds(300))
                        .build(),
                ProfilingSession.builder()
                        .sessionId(UUID.randomUUID().toString())
                        .target("com.example.service.PaymentService")
                        .profilingType("MEMORY")
                        .status("RUNNING")
                        .startTime(Instant.now().minusSeconds(60))
                        .build()
        );
        return Mono.just(ResponseEntity.ok(ApiResponse.success(sessions)));
    }

    @GetMapping("/session/{sessionId}/report")
    @Operation(summary = "获取剖析报告", description = "获取剖析会话的分析报告")
    @PreAuthorize("hasAuthority('profiling:read')")
    public Mono<ResponseEntity<ApiResponse<Map<String, Object>>>> getProfilingReport(
            @Parameter(description = "会话ID") @PathVariable @NotBlank(message = "会话ID不能为空") String sessionId) {
        log.info("获取剖析报告: sessionId={}", sessionId);
        Map<String, Object> report = Map.of(
                "sessionId", sessionId,
                "totalSamples", 10000,
                "hotspots", List.of(
                        Map.of("method", "com.example.service.UserService.getUser()", "cpuUsage", 45.5),
                        Map.of("method", "com.example.service.OrderService.processOrder()", "cpuUsage", 22.3)
                ),
                "summary", Map.of(
                        "totalDurationMs", 300000,
                        "avgCpuUsage", 65.5,
                        "peakMemoryUsageMb", 512
                )
        );
        return Mono.just(ResponseEntity.ok(ApiResponse.success(report)));
    }

    @DeleteMapping("/session/{sessionId}")
    @Operation(summary = "删除剖析会话", description = "删除指定的性能剖析会话")
    @PreAuthorize("hasAuthority('profiling:delete')")
    public Mono<ResponseEntity<ApiResponse<Void>>> deleteProfilingSession(
            @Parameter(description = "会话ID") @PathVariable @NotBlank(message = "会话ID不能为空") String sessionId) {
        log.info("删除性能剖析会话: sessionId={}", sessionId);
        return Mono.just(ResponseEntity.status(HttpStatus.NO_CONTENT)
                .body(ApiResponse.success("剖析会话删除成功", null)));
    }

    @PostMapping("/batch")
    @Operation(summary = "批量操作", description = "批量执行剖析操作")
    @PreAuthorize("hasAuthority('profiling:batch')")
    public Mono<ResponseEntity<ApiResponse<BatchOperationResult>>> batchOperation(
            @Valid @RequestBody BatchOperationRequest<ProfilingSession> request) {
        log.info("批量操作性能剖析: operationType={}", request.getOperationType());

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
