package com.device.platform.controller;

import com.device.platform.common.ApiResponse;
import com.device.platform.common.TraceContext;
import com.device.platform.dto.BackupRequest;
import com.device.platform.dto.RestoreRequest;
import com.device.platform.entity.BackupRecord;
import com.device.platform.storage.StorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/v1/storage")
@RequiredArgsConstructor
public class StorageController {

    private final StorageService storageService;

    @PostMapping("/backup")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<ApiResponse<BackupRecord>> createBackup(
            @Valid @RequestBody BackupRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return storageService.createBackup(request, ctx)
                .map(backup -> {
                    ApiResponse<BackupRecord> response = ApiResponse.success(201, backup);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @PostMapping("/restore")
    public Mono<ApiResponse<BackupRecord>> restoreBackup(
            @Valid @RequestBody RestoreRequest request,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return storageService.restoreBackup(request, ctx)
                .map(backup -> {
                    ApiResponse<BackupRecord> response = ApiResponse.success(backup);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/backups/{backupId}")
    public Mono<ApiResponse<BackupRecord>> getBackupStatus(
            @PathVariable String backupId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return storageService.getBackupStatus(backupId, ctx)
                .map(backup -> {
                    ApiResponse<BackupRecord> response = ApiResponse.success(backup);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @GetMapping("/backups")
    public Mono<ApiResponse<Flux<BackupRecord>>> listBackups(
            @RequestParam(required = false) String backupType,
            @RequestParam(required = false) String status,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return Mono.just(ApiResponse.success(
                storageService.listBackups(backupType, status, ctx)))
                .map(response -> {
                    response.setTraceId(ctx.getTraceId());
                    return response;
                });
    }

    @DeleteMapping("/backups/{backupId}")
    public Mono<ApiResponse<Void>> deleteBackup(
            @PathVariable String backupId,
            @RequestHeader(value = "X-Trace-Id", required = false) String traceId) {
        TraceContext ctx = new TraceContext(traceId);
        return storageService.deleteBackup(backupId, ctx)
                .then(Mono.fromCallable(() -> {
                    ApiResponse<Void> response = ApiResponse.success(null);
                    response.setTraceId(ctx.getTraceId());
                    return response;
                }));
    }
}
