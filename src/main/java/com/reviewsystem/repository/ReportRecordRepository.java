package com.reviewsystem.repository;

import com.reviewsystem.model.ReportRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReportRecordRepository extends JpaRepository<ReportRecord, String> {

    List<ReportRecord> findByCommentId(String commentId);

    List<ReportRecord> findByCommentIdOrderByReportedAtDesc(String commentId);

    List<ReportRecord> findByReportStatus(String reportStatus);

    List<ReportRecord> findByReportStatusOrderByPriorityDescReportedAtAsc(String reportStatus);

    List<ReportRecord> findByReportType(String reportType);

    List<ReportRecord> findByReportUserId(String reportUserId);

    long countByReportStatus(String reportStatus);

    long countByReportType(String reportType);

    @Query("SELECT COUNT(r) FROM ReportRecord r WHERE r.commentId IN (SELECT c.commentId FROM Comment c WHERE c.contentId = :contentId)")
    long countByContentId(@Param("contentId") String contentId);

    @Query("SELECT COUNT(r) FROM ReportRecord r WHERE r.commentId IN (SELECT c.commentId FROM Comment c WHERE c.contentId = :contentId) AND r.reportedAt BETWEEN :startTime AND :endTime")
    long countByContentIdAndTimeRange(@Param("contentId") String contentId,
                                      @Param("startTime") LocalDateTime startTime,
                                      @Param("endTime") LocalDateTime endTime);

    @Query("SELECT r FROM ReportRecord r WHERE r.reportStatus = 'pending' ORDER BY r.priority DESC, r.reportedAt ASC")
    List<ReportRecord> findPendingReportsOrdered();

    @Query("SELECT r FROM ReportRecord r WHERE r.reportedAt BETWEEN :startTime AND :endTime")
    List<ReportRecord> findByTimeRange(@Param("startTime") LocalDateTime startTime,
                                        @Param("endTime") LocalDateTime endTime);

    long countByReportStatusAndReportType(String reportStatus, String reportType);
}
