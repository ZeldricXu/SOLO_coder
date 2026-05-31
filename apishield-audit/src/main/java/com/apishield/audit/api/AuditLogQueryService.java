package com.apishield.audit.api;

import com.apishield.audit.domain.model.AuditLog;
import java.util.List;
import java.util.Optional;

public interface AuditLogQueryService {
    Optional<AuditLog> findById(String logId);
    List<AuditLog> findByOperator(String operatorId, int page, int size);
    List<AuditLog> findByResource(String resourceType, String resourceId);
    List<AuditLog> findByTimeRange(long startTime, long endTime);
    List<AuditLog> findByOperation(String operation, int page, int size);
}
