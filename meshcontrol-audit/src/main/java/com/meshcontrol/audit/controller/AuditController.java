package com.meshcontrol.audit.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.meshcontrol.common.response.ApiResponse;
import com.meshcontrol.common.response.PageResponse;
import com.meshcontrol.audit.dto.*;
import com.meshcontrol.audit.entity.AuditLog;
import com.meshcontrol.audit.entity.CommandLog;
import com.meshcontrol.audit.service.AuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @PostMapping("/commands")
    public Mono<ApiResponse<CommandLog>> recordCommand(@Valid @RequestBody CommandRecordRequest request) {
        return Mono.just(ApiResponse.created(auditService.recordCommand(request)));
    }

    @PutMapping("/commands/{commandId}/result")
    public Mono<ApiResponse<Boolean>> updateCommandResult(
            @PathVariable String commandId,
            @RequestParam String status,
            @RequestBody(required = false) Map<String, Object> result,
            @RequestParam(required = false) String errorMessage) {
        return Mono.just(ApiResponse.success(
                auditService.updateCommandResult(commandId, status, result, errorMessage)));
    }

    @GetMapping("/commands")
    public Mono<ApiResponse<PageResponse<CommandLog>>> queryCommands(@ModelAttribute CommandQueryRequest request) {
        IPage<CommandLog> page = auditService.queryCommands(request);
        return Mono.just(ApiResponse.success(PageResponse.of(page)));
    }

    @GetMapping("/commands/{commandId}")
    public Mono<ApiResponse<CommandLog>> getCommand(@PathVariable String commandId) {
        return Mono.just(ApiResponse.success(auditService.getCommand(commandId)));
    }

    @GetMapping("/commands/timeline/{aggregateType}/{aggregateId}")
    public Mono<ApiResponse<List<Map<String, Object>>>> getCommandTimeline(
            @PathVariable String aggregateType,
            @PathVariable String aggregateId) {
        return Mono.just(ApiResponse.success(
                auditService.getCommandTimeline(aggregateId, aggregateType)));
    }

    @PostMapping("/logs")
    public Mono<ApiResponse<AuditLog>> createAuditLog(
            @RequestParam String action,
            @RequestParam String resourceType,
            @RequestParam String resourceId,
            @RequestBody(required = false) Map<String, Object> oldValue,
            @RequestBody(required = false) Map<String, Object> newValue,
            @RequestParam(required = false) String operator,
            @RequestParam(required = false) String sourceIp,
            @RequestParam(required = false) String userAgent) {
        return Mono.just(ApiResponse.created(
                auditService.createAuditLog(action, resourceType, resourceId,
                        oldValue, newValue, operator, sourceIp, userAgent)));
    }

    @PostMapping("/logs/{auditId}/link-command/{commandId}")
    public Mono<ApiResponse<Boolean>> linkAuditToCommand(
            @PathVariable String auditId,
            @PathVariable String commandId) {
        return Mono.just(ApiResponse.success(auditService.linkAuditToCommand(auditId, commandId)));
    }

    @PostMapping("/logs/{auditId}/link-event/{eventId}")
    public Mono<ApiResponse<Boolean>> linkAuditToEvent(
            @PathVariable String auditId,
            @PathVariable String eventId) {
        return Mono.just(ApiResponse.success(auditService.linkAuditToEvent(auditId, eventId)));
    }

    @GetMapping("/logs")
    public Mono<ApiResponse<PageResponse<AuditLog>>> queryAuditLogs(@ModelAttribute AuditQueryRequest request) {
        IPage<AuditLog> page = auditService.queryAuditLogs(request);
        return Mono.just(ApiResponse.success(PageResponse.of(page)));
    }

    @GetMapping("/logs/resource/{resourceType}/{resourceId}")
    public Mono<ApiResponse<List<AuditLog>>> getAuditLogsForResource(
            @PathVariable String resourceType,
            @PathVariable String resourceId) {
        return Mono.just(ApiResponse.success(
                auditService.getAuditLogsForResource(resourceType, resourceId)));
    }

    @GetMapping("/logs/command/{commandId}")
    public Mono<ApiResponse<List<AuditLog>>> getAuditLogsForCommand(@PathVariable String commandId) {
        return Mono.just(ApiResponse.success(auditService.getAuditLogsForCommand(commandId)));
    }

    @PostMapping("/reports/compliance")
    public Mono<ApiResponse<Map<String, Object>>> generateComplianceReport(
            @Valid @RequestBody ComplianceReportRequest request) {
        return Mono.just(ApiResponse.success(auditService.generateComplianceReport(request)));
    }

    @GetMapping("/stats")
    public Mono<ApiResponse<Map<String, Object>>> getAuditStats() {
        return Mono.just(ApiResponse.success(auditService.getAuditStats()));
    }
}
