package com.datamasker.interfaces.controller;

import com.datamasker.application.service.AuditService;
import com.datamasker.domain.audit.model.AuditLogEntry;
import com.datamasker.domain.audit.model.TamperDetectionResult;
import com.datamasker.interfaces.assembler.AuditAssembler;
import com.datamasker.interfaces.dto.Result;
import com.datamasker.interfaces.dto.audit.AuditLogResponse;
import com.datamasker.interfaces.dto.audit.RecordLogRequest;
import com.datamasker.interfaces.dto.audit.VerificationResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/audit")
@RequiredArgsConstructor
public class AuditController {

    private final AuditService auditService;

    @PostMapping("/logs")
    public Result<AuditLogResponse> recordLog(@Valid @RequestBody RecordLogRequest request) {
        AuditLogEntry entry = auditService.recordLog(
                request.getOperation(),
                request.getOperator(),
                request.getModule(),
                request.getDetail()
        );
        return Result.success(AuditAssembler.toAuditLogResponse(entry));
    }

    @GetMapping("/verify")
    public Result<VerificationResponse> verifyIntegrity() {
        TamperDetectionResult result = auditService.verifyIntegrity();
        return Result.success(AuditAssembler.toVerificationResponse(result));
    }

    @GetMapping("/logs")
    public Result<List<AuditLogResponse>> getLogs(
            @RequestParam String module,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        List<AuditLogEntry> entries = auditService.getLogs(module, page, size);
        List<AuditLogResponse> responses = entries.stream()
                .map(AuditAssembler::toAuditLogResponse)
                .collect(Collectors.toList());
        return Result.success(responses);
    }
}
