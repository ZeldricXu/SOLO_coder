package com.reviewsystem.repository;

import com.reviewsystem.model.AuditRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface AuditRecordRepository extends JpaRepository<AuditRecord, String> {

    List<AuditRecord> findByCommentId(String commentId);

    List<AuditRecord> findByCommentIdOrderByAuditedAtDesc(String commentId);

    Optional<AuditRecord> findFirstByCommentIdOrderByAuditedAtDesc(String commentId);

    List<AuditRecord> findByAuditResult(String auditResult);

    List<AuditRecord> findByAuditType(String auditType);

    long countByAuditResult(String auditResult);

    @Query("SELECT COUNT(a) FROM AuditRecord a WHERE a.auditedAt BETWEEN :startTime AND :endTime")
    long countByTimeRange(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    @Query("SELECT COUNT(a) FROM AuditRecord a WHERE a.auditResult = :result AND a.auditedAt BETWEEN :startTime AND :endTime")
    long countByResultAndTimeRange(@Param("result") String result,
                                    @Param("startTime") LocalDateTime startTime,
                                    @Param("endTime") LocalDateTime endTime);

    @Query("SELECT a FROM AuditRecord a WHERE a.auditedAt BETWEEN :startTime AND :endTime ORDER BY a.auditedAt DESC")
    List<AuditRecord> findByTimeRange(@Param("startTime") LocalDateTime startTime,
                                       @Param("endTime") LocalDateTime endTime);
}
