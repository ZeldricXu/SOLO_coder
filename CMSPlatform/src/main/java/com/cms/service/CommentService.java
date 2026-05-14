package com.cms.service;

import com.cms.dto.CommentCreateRequest;
import com.cms.entity.Comment;
import com.cms.entity.Content;
import com.cms.entity.ContentStatistics;
import com.cms.exception.BusinessException;
import com.cms.repository.CommentRepository;
import com.cms.repository.ContentStatisticsRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class CommentService {

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private ContentService contentService;

    @Autowired
    private ContentStatisticsRepository contentStatisticsRepository;

    @Transactional
    public Comment createComment(CommentCreateRequest request) {
        Content content = contentService.getContentById(request.getContentId());

        if (!"published".equals(content.getContentStatus())) {
            throw new BusinessException(400, "未发布内容不可评论");
        }

        Comment comment = new Comment();
        comment.setCommentId("comment_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        comment.setContentId(request.getContentId());
        comment.setUserId(request.getUserId());
        comment.setUserName(request.getUserName());
        comment.setCommentContent(request.getCommentContent());
        comment.setCommentTime(LocalDateTime.now());
        comment.setCommentStatus("active");
        comment.setParentCommentId(request.getParentCommentId());
        comment.setLikeCount(0);

        Comment savedComment = commentRepository.save(comment);

        updateCommentStatistics(request.getContentId());

        return savedComment;
    }

    private void updateCommentStatistics(String contentId) {
        ContentStatistics statistics = contentStatisticsRepository.findByContentId(contentId)
                .orElseGet(() -> {
                    ContentStatistics newStat = new ContentStatistics();
                    newStat.setStatId("stat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
                    newStat.setContentId(contentId);
                    return newStat;
                });
        statistics.setCommentCount(statistics.getCommentCount() + 1);
        contentStatisticsRepository.save(statistics);
    }

    @Transactional
    public Comment likeComment(String commentId) {
        Comment comment = getCommentById(commentId);
        comment.setLikeCount(comment.getLikeCount() + 1);
        return commentRepository.save(comment);
    }

    @Transactional
    public Comment updateCommentStatus(String commentId, String status) {
        Comment comment = getCommentById(commentId);
        comment.setCommentStatus(status);
        return commentRepository.save(comment);
    }

    public Comment getCommentById(String commentId) {
        return commentRepository.findById(commentId)
                .orElseThrow(() -> new BusinessException(404, "评论不存在"));
    }

    public List<Comment> getCommentsByContentId(String contentId) {
        return commentRepository.findByContentIdAndCommentStatus(contentId, "active");
    }

    public List<Comment> getCommentsByUserId(String userId) {
        return commentRepository.findByUserId(userId);
    }

    public List<Comment> getReplies(String parentCommentId) {
        return commentRepository.findByParentCommentId(parentCommentId);
    }

    public long countCommentsByContentId(String contentId) {
        return commentRepository.countByContentIdAndStatus(contentId, "active");
    }

    @Transactional
    public void deleteComment(String commentId) {
        Comment comment = getCommentById(commentId);
        comment.setCommentStatus("deleted");
        commentRepository.save(comment);
    }
}
