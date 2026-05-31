package com.apishield.audit.service;

import com.apishield.application.service.ApplicationService;
import com.apishield.audit.domain.AuditLog;
import com.apishield.audit.dto.AuditLogRequest;
import com.apishield.audit.dto.AuditVerifyRequest;
import com.apishield.audit.dto.AuditVerifyResult;
import java.util.List;

public interface AuditLogService extends ApplicationService {
    AuditLog createLog(AuditLogRequest request);
    AuditLog getLogById(String logId);
    List<AuditLog> getLogsByOperator(String operatorId, int page, int size);
    List<AuditLog> getLogsByResource(String resourceType, String resourceId);
    AuditVerifyResult verifyIntegrity(AuditVerifyRequest request);
    AuditVerifyResult verifyFullChain();
    List<AuditLog> getLogsByTimeRange(long startTime, long endTime);
}
