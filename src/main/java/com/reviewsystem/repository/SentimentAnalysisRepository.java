package com.reviewsystem.repository;

import com.reviewsystem.model.SentimentAnalysis;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SentimentAnalysisRepository extends JpaRepository<SentimentAnalysis, String> {

    Optional<SentimentAnalysis> findByCommentId(String commentId);

    List<SentimentAnalysis> findBySentimentType(String sentimentType);

    long countBySentimentType(String sentimentType);

    @Query("SELECT COUNT(s) FROM SentimentAnalysis s WHERE s.commentId IN (SELECT c.commentId FROM Comment c WHERE c.contentId = :contentId) AND s.sentimentType = :type")
    long countByContentIdAndSentimentType(@Param("contentId") String contentId, @Param("type") String type);

    @Query("SELECT AVG(s.sentimentScore) FROM SentimentAnalysis s WHERE s.commentId IN (SELECT c.commentId FROM Comment c WHERE c.contentId = :contentId)")
    Double findAvgSentimentScoreByContentId(@Param("contentId") String contentId);

    @Query("SELECT s FROM SentimentAnalysis s WHERE s.analyzedAt BETWEEN :startTime AND :endTime")
    List<SentimentAnalysis> findByTimeRange(@Param("startTime") LocalDateTime startTime,
                                            @Param("endTime") LocalDateTime endTime);

    @Query("SELECT COUNT(s) FROM SentimentAnalysis s WHERE s.commentId IN (SELECT c.commentId FROM Comment c WHERE c.contentId = :contentId AND c.createdAt BETWEEN :startTime AND :endTime) AND s.sentimentType = :type")
    long countByContentIdAndTypeAndTimeRange(@Param("contentId") String contentId,
                                              @Param("type") String type,
                                              @Param("startTime") LocalDateTime startTime,
                                              @Param("endTime") LocalDateTime endTime);
}
