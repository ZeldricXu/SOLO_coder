package com.reviewsystem.repository;

import com.reviewsystem.model.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, String> {

    List<Comment> findByContentId(String contentId);

    Page<Comment> findByContentId(String contentId, Pageable pageable);

    List<Comment> findByUserId(String userId);

    List<Comment> findByCommentStatus(String commentStatus);

    Page<Comment> findByCommentStatus(String commentStatus, Pageable pageable);

    List<Comment> findByContentIdAndCommentStatus(String contentId, String commentStatus);

    Page<Comment> findByContentIdAndCommentStatus(String contentId, String commentStatus, Pageable pageable);

    List<Comment> findByUserIdAndCommentStatus(String userId, String commentStatus);

    long countByContentId(String contentId);

    long countByContentIdAndCommentStatus(String contentId, String commentStatus);

    long countByUserId(String userId);

    long countByAuditResult(String auditResult);

    @Query("SELECT c FROM Comment c WHERE c.contentId = :contentId AND c.commentStatus = 'published' ORDER BY c.recommendScore DESC")
    List<Comment> findRecommendedComments(@Param("contentId") String contentId, Pageable pageable);

    @Query("SELECT c FROM Comment c WHERE c.contentId = :contentId AND c.commentStatus = 'published' ORDER BY c.likeCount DESC")
    List<Comment> findHotComments(@Param("contentId") String contentId, Pageable pageable);

    @Query("SELECT c FROM Comment c WHERE c.contentId = :contentId AND c.commentStatus = 'published' ORDER BY c.createdAt DESC")
    List<Comment> findLatestComments(@Param("contentId") String contentId, Pageable pageable);

    @Query("SELECT AVG(c.qualityScore) FROM Comment c WHERE c.contentId = :contentId AND c.qualityScore IS NOT NULL")
    Double findAvgQualityScoreByContentId(@Param("contentId") String contentId);

    @Query("SELECT c FROM Comment c WHERE c.createdAt BETWEEN :startTime AND :endTime")
    List<Comment> findByTimeRange(@Param("startTime") LocalDateTime startTime, @Param("endTime") LocalDateTime endTime);

    @Query("SELECT c FROM Comment c WHERE c.contentId = :contentId AND c.createdAt BETWEEN :startTime AND :endTime")
    List<Comment> findByContentIdAndTimeRange(@Param("contentId") String contentId,
                                               @Param("startTime") LocalDateTime startTime,
                                               @Param("endTime") LocalDateTime endTime);

    @Query("SELECT COUNT(c) FROM Comment c WHERE c.contentId = :contentId AND c.createdAt BETWEEN :startTime AND :endTime")
    long countByContentIdAndTimeRange(@Param("contentId") String contentId,
                                      @Param("startTime") LocalDateTime startTime,
                                      @Param("endTime") LocalDateTime endTime);

    @Query("SELECT COUNT(c) FROM Comment c WHERE c.contentId = :contentId AND c.commentStatus = :status AND c.createdAt BETWEEN :startTime AND :endTime")
    long countByContentIdAndStatusAndTimeRange(@Param("contentId") String contentId,
                                               @Param("status") String status,
                                               @Param("startTime") LocalDateTime startTime,
                                               @Param("endTime") LocalDateTime endTime);

    @Query("SELECT SUM(c.likeCount) FROM Comment c WHERE c.contentId = :contentId")
    Long sumLikesByContentId(@Param("contentId") String contentId);

    @Query("SELECT SUM(c.replyCount) FROM Comment c WHERE c.contentId = :contentId")
    Long sumRepliesByContentId(@Param("contentId") String contentId);
}
