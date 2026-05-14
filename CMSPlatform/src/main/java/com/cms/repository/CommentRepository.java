package com.cms.repository;

import com.cms.entity.Comment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CommentRepository extends JpaRepository<Comment, String> {

    List<Comment> findByContentId(String contentId);

    List<Comment> findByContentIdAndCommentStatus(String contentId, String commentStatus);

    List<Comment> findByUserId(String userId);

    List<Comment> findByParentCommentId(String parentCommentId);

    @Query("SELECT COUNT(c) FROM Comment c WHERE c.contentId = :contentId")
    long countByContentId(@Param("contentId") String contentId);

    @Query("SELECT COUNT(c) FROM Comment c WHERE c.contentId = :contentId AND c.commentStatus = :status")
    long countByContentIdAndStatus(@Param("contentId") String contentId, @Param("status") String status);
}
