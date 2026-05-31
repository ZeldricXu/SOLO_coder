package com.datamasker.interfaces.assembler;

import com.datamasker.domain.audit.model.AuditLogEntry;
import com.datamasker.domain.audit.model.TamperDetectionResult;
import com.datamasker.interfaces.dto.audit.AuditLogResponse;
import com.datamasker.interfaces.dto.audit.VerificationResponse;

import java.time.format.DateTimeFormatter;

public class AuditAssembler {

    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static AuditLogResponse toAuditLogResponse(AuditLogEntry entry) {
        AuditLogResponse response = new AuditLogResponse();
        response.setLogId(entry.getLogId());
        response.setLogHash(entry.getLogHash());
        response.setOperation(entry.getOperation());
        response.setOperator(entry.getOperator());
        response.setModule(entry.getModule());
        response.setDetail(entry.getDetail());
        response.setTimestamp(entry.getTimestamp().format(FORMATTER));
        return response;
    }

    public static VerificationResponse toVerificationResponse(TamperDetectionResult result) {
        VerificationResponse response = new VerificationResponse();
        response.setVerified(result.isVerified());
        response.setTotalLogs(result.getTotalLogs());
        response.setTamperedCount(result.getTamperedCount());
        response.setTamperedIndices(result.getTamperedIndices());
        return response;
    }
}
