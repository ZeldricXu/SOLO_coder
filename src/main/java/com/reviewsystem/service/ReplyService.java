package com.reviewsystem.service;

import com.reviewsystem.dto.ReplyRequest;
import com.reviewsystem.model.Comment;
import com.reviewsystem.model.CommentHistory;
import com.reviewsystem.model.ReplyRecord;
import com.reviewsystem.repository.CommentHistoryRepository;
import com.reviewsystem.repository.CommentRepository;
import com.reviewsystem.repository.ReplyRecordRepository;
import com.reviewsystem.util.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ReplyService {

    private static final Logger logger = LoggerFactory.getLogger(ReplyService.class);

    @Autowired
    private ReplyRecordRepository replyRecordRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private CommentHistoryRepository commentHistoryRepository;

    @Autowired
    private HistoryService historyService;

    @Transactional
    public Map<String, Object> addReply(ReplyRequest request) {
        Map<String, Object> result = new HashMap<>();

        Optional<Comment> commentOpt = commentRepository.findById(request.getCommentId());
        if (commentOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "评论不存在");
            return result;
        }

        Comment comment = commentOpt.get();

        String content = request.getReplyContent().trim();
        if (content.isEmpty()) {
            result.put("success", false);
            result.put("message", "回复内容不能为空");
            return result;
        }

        if (content.length() > 1000) {
            result.put("success", false);
            result.put("message", "回复内容不能超过1000字");
            return result;
        }

        ReplyRecord reply = new ReplyRecord();
        reply.setReplyId(IdGenerator.generateReplyId());
        reply.setCommentId(request.getCommentId());
        reply.setReplyUser(request.getReplyUser());
        reply.setReplyContent(content);
        reply.setReplyStatus("active");
        reply.setLikeCount(0);
        reply.setParentReplyId(request.getParentReplyId());
        replyRecordRepository.save(reply);

        comment.setReplyCount((comment.getReplyCount() != null ? comment.getReplyCount() : 0) + 1);
        commentRepository.save(comment);

        historyService.recordHistory(request.getCommentId(), "REPLY_ADD",
                "添加回复: " + request.getReplyUser(),
                null, null, null, null,
                request.getReplyUser(), "user");

        result.put("success", true);
        result.put("reply_id", reply.getReplyId());
        result.put("comment_id", request.getCommentId());
        result.put("reply_user", request.getReplyUser());
        result.put("reply_time", reply.getReplyTime());

        logger.info("回复添加成功: replyId={}, commentId={}, user={}",
                reply.getReplyId(), request.getCommentId(), request.getReplyUser());
        return result;
    }

    public List<ReplyRecord> getReplies(String commentId) {
        return replyRecordRepository.findByCommentIdAndReplyStatusOrderByReplyTimeAsc(commentId, "active");
    }

    public Optional<ReplyRecord> getReply(String replyId) {
        return replyRecordRepository.findById(replyId);
    }

    @Transactional
    public Map<String, Object> deleteReply(String replyId, String operator) {
        Map<String, Object> result = new HashMap<>();

        Optional<ReplyRecord> replyOpt = replyRecordRepository.findById(replyId);
        if (replyOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "回复不存在");
            return result;
        }

        ReplyRecord reply = replyOpt.get();
        reply.setReplyStatus("deleted");
        replyRecordRepository.save(reply);

        Optional<Comment> commentOpt = commentRepository.findById(reply.getCommentId());
        if (commentOpt.isPresent()) {
            Comment comment = commentOpt.get();
            int currentCount = comment.getReplyCount() != null ? comment.getReplyCount() : 0;
            comment.setReplyCount(Math.max(0, currentCount - 1));
            commentRepository.save(comment);
        }

        historyService.recordHistory(reply.getCommentId(), "REPLY_DELETE",
                "删除回复: " + reply.getReplyId(),
                null, null, null, null,
                operator, "admin");

        result.put("success", true);
        result.put("reply_id", replyId);

        logger.info("回复删除成功: replyId={}, operator={}", replyId, operator);
        return result;
    }

    @Transactional
    public Map<String, Object> likeReply(String replyId) {
        Map<String, Object> result = new HashMap<>();

        Optional<ReplyRecord> replyOpt = replyRecordRepository.findById(replyId);
        if (replyOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "回复不存在");
            return result;
        }

        ReplyRecord reply = replyOpt.get();
        int currentLikes = reply.getLikeCount() != null ? reply.getLikeCount() : 0;
        reply.setLikeCount(currentLikes + 1);
        replyRecordRepository.save(reply);

        result.put("success", true);
        result.put("reply_id", replyId);
        result.put("like_count", reply.getLikeCount());

        return result;
    }

    @Transactional
    public Map<String, Object> editReply(String replyId, String newContent, String operator) {
        Map<String, Object> result = new HashMap<>();

        Optional<ReplyRecord> replyOpt = replyRecordRepository.findById(replyId);
        if (replyOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "回复不存在");
            return result;
        }

        ReplyRecord reply = replyOpt.get();

        if (!reply.getReplyUser().equals(operator)) {
            result.put("success", false);
            result.put("message", "没有权限编辑此回复");
            return result;
        }

        String content = newContent.trim();
        if (content.isEmpty()) {
            result.put("success", false);
            result.put("message", "回复内容不能为空");
            return result;
        }

        if (content.length() > 1000) {
            result.put("success", false);
            result.put("message", "回复内容不能超过1000字");
            return result;
        }

        reply.setReplyContent(content);
        replyRecordRepository.save(reply);

        result.put("success", true);
        result.put("reply_id", replyId);

        logger.info("回复编辑成功: replyId={}", replyId);
        return result;
    }

    public long countRepliesByComment(String commentId) {
        return replyRecordRepository.countByCommentIdAndReplyStatus(commentId, "active");
    }

    public List<ReplyRecord> getRepliesByUser(String replyUser) {
        return replyRecordRepository.findByReplyUser(replyUser);
    }

    public List<ReplyRecord> getChildReplies(String parentReplyId) {
        return replyRecordRepository.findByParentReplyId(parentReplyId);
    }
}
