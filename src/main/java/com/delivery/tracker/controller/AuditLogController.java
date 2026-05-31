package com.delivery.tracker.controller;

import com.delivery.tracker.common.Result;
import com.delivery.tracker.entity.AuditLog;
import com.delivery.tracker.service.AuditLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogService auditLogService;

    @PostMapping
    public Mono<Result<AuditLog>> log(@RequestBody Map<String, Object> request) {
        String userId = (String) request.getOrDefault("userId", "system");
        String operation = (String) request.get("operation");
        String resourceType = (String) request.get("resourceType");
        String resourceId = (String) request.get("resourceId");
        @SuppressWarnings("unchecked")
        Map<String, Object> detail = (Map<String, Object>) request.get("detail");

        return auditLogService.log(userId, operation, resourceType, resourceId, detail)
                .map(Result::success);
    }

    @GetMapping
    public Mono<Result<List<AuditLog>>> getLogs(
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {

        return auditLogService.getLogs(resourceType, resourceId, startTime, endTime)
                .collectList()
                .map(Result::success);
    }

    @GetMapping("/{logId}")
    public Mono<Result<AuditLog>> getLog(@PathVariable String logId) {
        return auditLogService.getLog(logId)
                .map(Result::success);
    }

    @PostMapping("/verify")
    public Mono<Result<Map<String, Object>>> verifyIntegrity() {
        return auditLogService.verifyIntegrity()
                .flatMap(valid -> auditLogService.getChainInfo()
                        .map(info -> {
                            info.put("integrityValid", valid);
                            return Result.success(info);
                        })
                );
    }

    @GetMapping("/chain-info")
    public Mono<Result<Map<String, Object>>> getChainInfo() {
        return auditLogService.getChainInfo()
                .map(Result::success);
    }
}
