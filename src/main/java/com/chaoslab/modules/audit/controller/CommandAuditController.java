package com.chaoslab.modules.audit.controller;

import com.chaoslab.common.ApiResponse;
import com.chaoslab.entity.AuditLog;
import com.chaoslab.entity.CommandLog;
import com.chaoslab.entity.ComplianceReport;
import com.chaoslab.modules.audit.dto.AuditLogQueryRequest;
import com.chaoslab.modules.audit.dto.CommandSubmitRequest;
import com.chaoslab.modules.audit.dto.ComplianceReportRequest;
import com.chaoslab.modules.audit.service.CommandAuditService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CommandAuditController {

    private final CommandAuditService commandAuditService;

    @PostMapping("/commands")
    public Mono<ApiResponse<CommandLog>> submitCommand(
            @Valid @RequestBody CommandSubmitRequest request) {
        return commandAuditService.submitCommand(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/commands/{commandId}")
    public Mono<ApiResponse<CommandLog>> getCommand(@PathVariable String commandId) {
        return commandAuditService.getCommand(commandId)
                .map(ApiResponse::success);
    }

    @GetMapping("/commands")
    public Mono<ApiResponse<List<CommandLog>>> listCommands(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String commandType,
            @RequestParam(required = false) String createdBy) {
        return commandAuditService.listCommands(status, commandType, createdBy)
                .map(ApiResponse::success);
    }

    @PostMapping("/audit/query")
    public Mono<ApiResponse<List<AuditLog>>> queryAuditLogs(
            @Valid @RequestBody AuditLogQueryRequest request) {
        return commandAuditService.queryAuditLogs(request)
                .map(ApiResponse::success);
    }

    @PostMapping("/reports")
    public Mono<ApiResponse<ComplianceReport>> generateReport(
            @Valid @RequestBody ComplianceReportRequest request) {
        return commandAuditService.generateComplianceReport(request)
                .map(ApiResponse::success);
    }

    @GetMapping("/reports/{reportId}")
    public Mono<ApiResponse<ComplianceReport>> getReport(@PathVariable String reportId) {
        return commandAuditService.getReport(reportId)
                .map(ApiResponse::success);
    }

    @GetMapping("/reports")
    public Mono<ApiResponse<List<ComplianceReport>>> listReports(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String status) {
        return commandAuditService.listReports(type, status)
                .map(ApiResponse::success);
    }

    @GetMapping("/audit/stats")
    public Mono<ApiResponse<Map<String, Object>>> getAuditStats() {
        return commandAuditService.getAuditStats()
                .map(ApiResponse::success);
    }
}
