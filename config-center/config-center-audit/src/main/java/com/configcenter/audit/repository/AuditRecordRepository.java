package com.configcenter.audit.repository;

import com.configcenter.common.entity.AuditRecord;
import com.configcenter.common.enums.AuditOperation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AuditRecordRepository extends JpaRepository<AuditRecord, String>, JpaSpecificationExecutor<AuditRecord> {

    List<AuditRecord> findByConfigIdOrderByOperatedAtDesc(String configId);

    List<AuditRecord> findByOperatorOrderByOperatedAtDesc(String operator);

    List<AuditRecord> findByOperationOrderByOperatedAtDesc(AuditOperation operation);

    @Query("SELECT a FROM AuditRecord a WHERE a.configId = :configId ORDER BY a.operatedAt DESC")
    List<AuditRecord> findLatestByConfigId(@Param("configId") String configId, org.springframework.data.domain.Pageable pageable);

    @Query("SELECT a FROM AuditRecord a WHERE a.operatedAt BETWEEN :startTime AND :endTime ORDER BY a.operatedAt DESC")
    List<AuditRecord> findByTimeRange(
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT a FROM AuditRecord a WHERE a.configId = :configId AND a.operation = :operation ORDER BY a.operatedAt DESC")
    List<AuditRecord> findByConfigIdAndOperation(
            @Param("configId") String configId,
            @Param("operation") AuditOperation operation);
}
