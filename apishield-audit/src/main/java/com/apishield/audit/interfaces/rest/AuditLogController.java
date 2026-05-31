package com.apishield.audit.interfaces.rest;

import com.apishield.common.dto.Result;
import com.apishield.audit.api.AuditFacade;
import com.apishield.audit.api.dto.CreateAuditLogRequest;
import com.apishield.audit.api.dto.VerifyAuditRequest;
import com.apishield.audit.domain.model.AuditLog;
import com.apishield.audit.domain.model.AuditVerifyResult;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditFacade auditFacade;

    @PostMapping("/logs")
    public Mono<Result<AuditLog>> createLog(@RequestBody CreateAuditLogRequest request) {
        return Mono.just(Result.success(
            auditFacade.createLog(
                request.getOperation(),
                request.getOperatorId(),
                request.getOperatorName(),
                request.getResourceType(),
                request.getResourceId(),
                request.getRequestParams(),
                request.getResponseResult(),
                request.getStatus(),
                request.getIpAddress(),
                request.getUserAgent()
            )
        ));
    }

    @GetMapping("/logs/{logId}")
    public Mono<Result<AuditLog>> getLog(@PathVariable String logId) {
        return Mono.just(auditFacade.findById(logId)
                .map(Result::success)
                .orElseGet(() -> Result.error("NOT_FOUND", "审计日志不存在")));
    }

    @GetMapping("/logs/operator/{operatorId}")
    public Mono<Result<List<AuditLog>>> getLogsByOperator(
            @PathVariable String operatorId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Mono.just(Result.success(auditFacade.findByOperator(operatorId, page, size)));
    }

    @GetMapping("/logs/resource/{resourceType}/{resourceId}")
    public Mono<Result<List<AuditLog>>> getLogsByResource(
            @PathVariable String resourceType,
            @PathVariable String resourceId) {
        return Mono.just(Result.success(auditFacade.findByResource(resourceType, resourceId)));
    }

    @GetMapping("/logs/range")
    public Mono<Result<List<AuditLog>>> getLogsByTimeRange(
            @RequestParam long startTime,
            @RequestParam long endTime) {
        return Mono.just(Result.success(auditFacade.findByTimeRange(startTime, endTime)));
    }

    @PostMapping("/verify")
    public Mono<Result<AuditVerifyResult>> verifyLogs(@RequestBody VerifyAuditRequest request) {
        AuditVerifyResult result;
        if (request.isVerifyFullChain()) {
            result = auditFacade.verifyFullChain();
        } else if (request.getStartHeight() != null && request.getEndHeight() != null) {
            result = auditFacade.verifyBlockRange(request.getStartHeight(), request.getEndHeight());
        } else if (request.getLogIds() != null && !request.getLogIds().isEmpty()) {
            result = auditFacade.verifyLogIntegrity(request.getLogIds());
        } else {
            result = new AuditVerifyResult();
            result.setValid(false);
            result.setMessage("请指定验证范围");
        }
        return Mono.just(Result.success(result));
    }

    @PostMapping("/verify/full")
    public Mono<Result<AuditVerifyResult>> verifyFullChain() {
        return Mono.just(Result.success(auditFacade.verifyFullChain()));
    }

    @GetMapping("/verify/{logId}")
    public Mono<Result<Boolean>> verifySingleLog(@PathVariable String logId) {
        return Mono.just(Result.success(auditFacade.verifySingleLog(logId)));
    }

    @GetMapping("/chain/info")
    public Mono<Result<java.util.Map<String, Object>>> getChainInfo() {
        java.util.Map<String, Object> info = new java.util.HashMap<>();
        info.put("lastHash", auditFacade.getLastHash());
        info.put("blockHeight", auditFacade.getCurrentBlockHeight());
        return Mono.just(Result.success(info));
    }
}
