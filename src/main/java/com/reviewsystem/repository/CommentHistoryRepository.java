package com.reviewsystem.repository;

import com.reviewsystem.model.CommentHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface CommentHistoryRepository extends JpaRepository<CommentHistory, String> {

    List<CommentHistory> findByCommentId(String commentId);

    List<CommentHistory> findByCommentIdOrderByActionTimeDesc(String commentId);

    List<CommentHistory> findByOperator(String operator);

    List<CommentHistory> findByActionType(String actionType);

    List<CommentHistory> findByOperatorType(String operatorType);

    @Query("SELECT h FROM CommentHistory h WHERE h.actionTime BETWEEN :startTime AND :endTime ORDER BY h.actionTime DESC")
    List<CommentHistory> findByTimeRange(@Param("startTime") LocalDateTime startTime,
                                          @Param("endTime") LocalDateTime endTime);

    @Query("SELECT h FROM CommentHistory h WHERE h.commentId IN (SELECT c.commentId FROM Comment c WHERE c.contentId = :contentId) ORDER BY h.actionTime DESC")
    List<CommentHistory> findByContentId(@Param("contentId") String contentId);

    @Query("SELECT h FROM CommentHistory h WHERE h.commentId = :commentId AND h.actionTime BETWEEN :startTime AND :endTime ORDER BY h.actionTime DESC")
    List<CommentHistory> findByCommentIdAndTimeRange(@Param("commentId") String commentId,
                                                     @Param("startTime") LocalDateTime startTime,
                                                     @Param("endTime") LocalDateTime endTime);
}
