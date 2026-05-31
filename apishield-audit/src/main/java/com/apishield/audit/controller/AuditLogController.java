package com.apishield.audit.controller;

import com.apishield.common.dto.Result;
import com.apishield.audit.domain.AuditLog;
import com.apishield.audit.dto.AuditLogRequest;
import com.apishield.audit.dto.AuditVerifyRequest;
import com.apishield.audit.dto.AuditVerifyResult;
import com.apishield.audit.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @PostMapping("/logs")
    public Mono<Result<AuditLog>> createLog(@RequestBody AuditLogRequest request) {
        return Mono.just(Result.success(auditLogService.createLog(request)));
    }

    @GetMapping("/logs/{logId}")
    public Mono<Result<AuditLog>> getLog(@PathVariable String logId) {
        return Mono.just(Result.success(auditLogService.getLogById(logId)));
    }

    @GetMapping("/logs/operator/{operatorId}")
    public Mono<Result<List<AuditLog>>> getLogsByOperator(
            @PathVariable String operatorId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Mono.just(Result.success(auditLogService.getLogsByOperator(operatorId, page, size)));
    }

    @GetMapping("/logs/resource/{resourceType}/{resourceId}")
    public Mono<Result<List<AuditLog>>> getLogsByResource(
            @PathVariable String resourceType,
            @PathVariable String resourceId) {
        return Mono.just(Result.success(auditLogService.getLogsByResource(resourceType, resourceId)));
    }

    @PostMapping("/verify")
    public Mono<Result<AuditVerifyResult>> verifyLogs(@RequestBody AuditVerifyRequest request) {
        return Mono.just(Result.success(auditLogService.verifyIntegrity(request)));
    }

    @PostMapping("/verify/full")
    public Mono<Result<AuditVerifyResult>> verifyFullChain() {
        return Mono.just(Result.success(auditLogService.verifyFullChain()));
    }

    @GetMapping("/logs/range")
    public Mono<Result<List<AuditLog>>> getLogsByTimeRange(
            @RequestParam long startTime,
            @RequestParam long endTime) {
        return Mono.just(Result.success(auditLogService.getLogsByTimeRange(startTime, endTime)));
    }
}
