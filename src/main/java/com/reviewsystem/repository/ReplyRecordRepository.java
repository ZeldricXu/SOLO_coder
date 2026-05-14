package com.reviewsystem.repository;

import com.reviewsystem.model.ReplyRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ReplyRecordRepository extends JpaRepository<ReplyRecord, String> {

    List<ReplyRecord> findByCommentId(String commentId);

    List<ReplyRecord> findByCommentIdOrderByReplyTimeAsc(String commentId);

    List<ReplyRecord> findByCommentIdAndReplyStatusOrderByReplyTimeAsc(String commentId, String replyStatus);

    List<ReplyRecord> findByReplyUser(String replyUser);

    List<ReplyRecord> findByParentReplyId(String parentReplyId);

    long countByCommentId(String commentId);

    long countByCommentIdAndReplyStatus(String commentId, String replyStatus);

    @Query("SELECT COUNT(r) FROM ReplyRecord r WHERE r.commentId IN (SELECT c.commentId FROM Comment c WHERE c.contentId = :contentId)")
    long countByContentId(@Param("contentId") String contentId);

    @Query("SELECT r FROM ReplyRecord r WHERE r.replyTime BETWEEN :startTime AND :endTime")
    List<ReplyRecord> findByTimeRange(@Param("startTime") LocalDateTime startTime,
                                       @Param("endTime") LocalDateTime endTime);
}
