package com.solo.config.controller;

import com.solo.config.common.Result;
import com.solo.config.entity.AuditLog;
import com.solo.config.entity.Command;
import com.solo.config.module.audit.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @PostMapping("/commands")
    public Mono<Result<Command>> recordCommand(@RequestBody Map<String, Object> request) {
        String commandType = (String) request.get("commandType");
        String aggregateId = (String) request.get("aggregateId");
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) request.get("payload");
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) request.get("metadata");

        return auditService.recordCommand(commandType, aggregateId, payload, metadata)
                .map(Result::success);
    }

    @GetMapping("/commands")
    public Flux<Command> listCommands(
            @RequestParam(required = false) String commandType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return auditService.listCommands(commandType, status, page, size);
    }

    @GetMapping("/commands/{commandId}")
    public Mono<Result<Command>> getCommand(@PathVariable String commandId) {
        return auditService.getCommand(commandId)
                .map(Result::success)
                .defaultIfEmpty(Result.error(404, "命令不存在"));
    }

    @PutMapping("/commands/{commandId}/status")
    public Mono<Result<Command>> updateCommandStatus(
            @PathVariable String commandId,
            @RequestBody Map<String, Object> request) {
        String status = (String) request.get("status");
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) request.get("result");
        return auditService.updateCommandStatus(commandId, status, result)
                .map(Result::success);
    }

    @GetMapping("/commands/aggregate/{aggregateId}")
    public Flux<Command> getCommandsByAggregate(@PathVariable String aggregateId) {
        return auditService.getCommandsByAggregate(aggregateId);
    }

    @GetMapping("/logs")
    public Flux<AuditLog> listAuditLogs(
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String resourceType,
            @RequestParam(required = false) String resourceId,
            @RequestParam(required = false) String operation,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return auditService.listAuditLogs(userId, resourceType, resourceId, operation, startTime, endTime, page, size);
    }

    @GetMapping("/logs/{auditId}")
    public Mono<Result<AuditLog>> getAuditLog(@PathVariable String auditId) {
        return auditService.getAuditLog(auditId)
                .map(Result::success)
                .defaultIfEmpty(Result.error(404, "审计日志不存在"));
    }

    @PostMapping("/logs")
    public Mono<Result<AuditLog>> recordAuditLog(@RequestBody Map<String, Object> request) {
        String userId = (String) request.get("userId");
        String operation = (String) request.get("operation");
        String resourceType = (String) request.get("resourceType");
        String resourceId = (String) request.get("resourceId");
        @SuppressWarnings("unchecked")
        Map<String, Object> oldValue = (Map<String, Object>) request.get("oldValue");
        @SuppressWarnings("unchecked")
        Map<String, Object> newValue = (Map<String, Object>) request.get("newValue");
        String ipAddress = (String) request.get("ipAddress");
        String userAgent = (String) request.get("userAgent");

        return auditService.recordAuditLog(userId, operation, resourceType, resourceId,
                        oldValue, newValue, ipAddress, userAgent)
                .map(Result::success);
    }

    @GetMapping("/report/compliance")
    public Mono<Result<Map<String, Object>>> generateComplianceReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime startTime,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime endTime) {
        return auditService.generateComplianceReport(startTime, endTime)
                .map(Result::success);
    }
}
