package com.reviewsystem.repository;

import com.reviewsystem.model.QualityEvaluation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface QualityEvaluationRepository extends JpaRepository<QualityEvaluation, String> {

    Optional<QualityEvaluation> findByCommentId(String commentId);

    List<QualityEvaluation> findByIsViolation(Boolean isViolation);

    List<QualityEvaluation> findByIsSpam(Boolean isSpam);

    List<QualityEvaluation> findByViolationType(String violationType);

    List<QualityEvaluation> findByEvaluationLevel(String evaluationLevel);

    long countByIsViolation(Boolean isViolation);

    long countByViolationType(String violationType);

    @Query("SELECT AVG(q.qualityScore) FROM QualityEvaluation q WHERE q.commentId IN (SELECT c.commentId FROM Comment c WHERE c.contentId = :contentId)")
    Double findAvgQualityScoreByContentId(@Param("contentId") String contentId);

    @Query("SELECT COUNT(q) FROM QualityEvaluation q WHERE q.commentId IN (SELECT c.commentId FROM Comment c WHERE c.contentId = :contentId) AND q.isViolation = true")
    long countViolationsByContentId(@Param("contentId") String contentId);

    @Query("SELECT q FROM QualityEvaluation q WHERE q.evaluatedAt BETWEEN :startTime AND :endTime")
    List<QualityEvaluation> findByTimeRange(@Param("startTime") LocalDateTime startTime,
                                            @Param("endTime") LocalDateTime endTime);
}
