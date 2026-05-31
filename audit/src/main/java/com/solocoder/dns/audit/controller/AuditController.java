package com.solocoder.dns.audit.controller;

import com.solocoder.dns.common.model.ApiResponse;
import com.solocoder.dns.common.model.PageResult;
import com.solocoder.dns.audit.model.AuditLog;
import com.solocoder.dns.audit.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {
    private final AuditService auditService;

    @GetMapping("/logs")
    public ApiResponse<PageResult<AuditLog>> queryLogs(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String userId,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String resourceType) {
        return ApiResponse.success(auditService.queryAuditLogs(page, size, userId, action, resourceType));
    }

    @GetMapping("/logs/resource/{type}/{id}")
    public ApiResponse<List<AuditLog>> getResourceLogs(@PathVariable String type, @PathVariable String id) {
        return ApiResponse.success(auditService.getAuditLogsForResource(type, id));
    }

    @PostMapping("/report")
    public ApiResponse<Map<String, Object>> generateReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime start,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime end) {
        return ApiResponse.success(auditService.generateComplianceReport(start, end));
    }
}
