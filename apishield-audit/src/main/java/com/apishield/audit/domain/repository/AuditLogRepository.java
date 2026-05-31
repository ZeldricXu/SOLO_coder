package com.apishield.audit.domain.repository;

import com.apishield.audit.domain.model.AuditLog;
import java.util.List;
import java.util.Optional;

public interface AuditLogRepository {
    AuditLog save(AuditLog log);
    Optional<AuditLog> findById(String logId);
    List<AuditLog> findByOperatorId(String operatorId, int page, int size);
    List<AuditLog> findByResource(String resourceType, String resourceId);
    List<AuditLog> findByTimeRange(long startTime, long endTime);
    List<AuditLog> findByOperation(String operation, int page, int size);
    List<AuditLog> findByBlockHeightRange(int startHeight, int endHeight);
    List<AuditLog> findAll();
    List<AuditLog> findByIds(List<String> logIds);
}
