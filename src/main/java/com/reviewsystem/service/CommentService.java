package com.reviewsystem.service;

import com.reviewsystem.dto.CommentEditRequest;
import com.reviewsystem.dto.CommentPublishRequest;
import com.reviewsystem.model.*;
import com.reviewsystem.repository.*;
import com.reviewsystem.rule.AuditRuleManager;
import com.reviewsystem.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.*;

@Service
public class CommentService {

    private static final Logger logger = LoggerFactory.getLogger(CommentService.class);

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @Autowired
    private SentimentAnalysisRepository sentimentAnalysisRepository;

    @Autowired
    private QualityEvaluationRepository qualityEvaluationRepository;

    @Autowired
    private RecommendRecordRepository recommendRecordRepository;

    @Autowired
    private CommentHistoryRepository commentHistoryRepository;

    @Autowired
    private CommentStatRepository commentStatRepository;

    @Autowired
    private SentimentAnalyzer sentimentAnalyzer;

    @Autowired
    private QualityEvaluator qualityEvaluator;

    @Autowired
    private AuditService auditService;

    @Resource
    private AuditRuleManager auditRuleManager;

    @Transactional
    public Map<String, Object> publishComment(CommentPublishRequest request) {
        Map<String, Object> result = new HashMap<>();

        logger.debug("开始处理评论发布请求: userId={}, contentId={}",
                request.getUserId(), request.getContentId());

        String content = request.getCommentContent().trim();
        if (content.isEmpty()) {
            result.put("success", false);
            result.put("message", "评论内容不能为空");
            result.put("code", "EMPTY_CONTENT");
            return result;
        }

        int maxLength = auditRuleManager.getMaxLength();
        int minLength = auditRuleManager.getMinLength();

        if (content.length() > maxLength) {
            result.put("success", false);
            result.put("message", "评论内容不能超过" + maxLength + "字");
            result.put("code", "CONTENT_TOO_LONG");
            return result;
        }

        if (content.length() < minLength) {
            result.put("success", false);
            result.put("message", "评论内容至少需要" + minLength + "字");
            result.put("code", "CONTENT_TOO_SHORT");
            return result;
        }

        String commentId = IdGenerator.generateCommentId();

        Comment comment = new Comment();
        comment.setCommentId(commentId);
        comment.setContentId(request.getContentId());
        comment.setUserId(request.getUserId());
        comment.setCommentContent(content);
        comment.setCommentType(request.getCommentType() != null ? request.getCommentType() : "text");
        comment.setCommentStatus("pending");
        comment.setAuditResult("pending");

        commentRepository.save(comment);

        AuditRecord initialAudit = new AuditRecord();
        initialAudit.setAuditId(IdGenerator.generateAuditId());
        initialAudit.setCommentId(commentId);
        initialAudit.setAuditType("auto");
        initialAudit.setAuditRules(Arrays.asList("queue_submit"));
        initialAudit.setAuditResult("pending");
        initialAudit.setAuditReason("评论已提交，等待异步审核");
        auditRecordRepository.save(initialAudit);

        Map<String, Object> queueResult = auditService.submitAuditTask(comment);

        saveCommentHistory(commentId, "PUBLISH", "评论发布，已提交审核队列",
                null, "pending", null, content,
                request.getUserId(), "user");

        updateCommentStats(request.getContentId(), "pending");

        result.put("success", true);
        result.put("comment_id", commentId);
        result.put("status", "pending");
        result.put("audit_result", "pending");
        result.put("message", "评论已提交，正在等待异步审核");
        result.put("task_id", queueResult.get("task_id"));
        result.put("queue_position", queueResult.get("message"));

        logger.info("评论发布完成，已提交审核队列: commentId={}", commentId);
        return result;
    }

    private void saveQualityEvaluation(String commentId, QualityEvaluator.QualityResult qualityResult) {
        Optional<QualityEvaluation> existing = qualityEvaluationRepository.findByCommentId(commentId);

        QualityEvaluation evaluation;
        if (existing.isPresent()) {
            evaluation = existing.get();
        } else {
            evaluation = new QualityEvaluation();
            evaluation.setEvaluationId(IdGenerator.generateEvaluationId());
            evaluation.setCommentId(commentId);
        }

        evaluation.setQualityScore(qualityResult.getQualityScore());
        evaluation.setLengthScore(qualityResult.getLengthScore());
        evaluation.setRelevanceScore(qualityResult.getRelevanceScore());
        evaluation.setReadabilityScore(qualityResult.getReadabilityScore());
        evaluation.setViolationScore(qualityResult.getViolationScore());
        evaluation.setViolation(qualityResult.isViolation());
        evaluation.setViolationType(qualityResult.getViolationType());
        evaluation.setViolationReason(qualityResult.getViolationReason());
        evaluation.setSpamScore(qualityResult.isSpam() ? 80 : 0);
        evaluation.setSpam(qualityResult.isSpam());
        evaluation.setEvaluationLevel(qualityResult.getEvaluationLevel());
        qualityEvaluationRepository.save(evaluation);
    }

    private void saveSentimentAnalysis(String commentId, SentimentAnalyzer.SentimentResult sentimentResult) {
        Optional<SentimentAnalysis> existing = sentimentAnalysisRepository.findByCommentId(commentId);

        SentimentAnalysis analysis;
        if (existing.isPresent()) {
            analysis = existing.get();
        } else {
            analysis = new SentimentAnalysis();
            analysis.setSentimentId(IdGenerator.generateSentimentId());
            analysis.setCommentId(commentId);
        }

        analysis.setSentimentType(sentimentResult.getSentimentType());
        analysis.setSentimentScore(sentimentResult.getSentimentScore());
        analysis.setPositiveScore(sentimentResult.getPositiveScore());
        analysis.setNegativeScore(sentimentResult.getNegativeScore());
        analysis.setNeutralScore(sentimentResult.getNeutralScore());
        analysis.setSentimentKeywords(sentimentResult.getSentimentKeywords());
        sentimentAnalysisRepository.save(analysis);
    }

    private void saveRecommendRecord(Comment comment, int recommendScore,
                                     int qualityScore, double sentimentScore) {
        Optional<RecommendRecord> existing = recommendRecordRepository.findByCommentId(comment.getCommentId());

        RecommendRecord record;
        if (existing.isPresent()) {
            record = existing.get();
        } else {
            record = new RecommendRecord();
            record.setRecommendId(IdGenerator.generateRecommendId());
            record.setCommentId(comment.getCommentId());
            record.setContentId(comment.getContentId());
        }

        record.setRecommendType("quality");
        record.setRecommendScore(recommendScore);
        record.setQualityFactor(qualityScore);
        record.setHeatFactor(0);
        record.setTimeFactor(100);
        record.setSentimentFactor((int) (sentimentScore * 100));
        record.setRecommendPosition(0);
        recommendRecordRepository.save(record);
    }

    private void saveCommentHistory(String commentId, String actionType,
                                    String description, String oldStatus,
                                    String newStatus, String oldContent,
                                    String newContent, String operator,
                                    String operatorType) {
        CommentHistory history = new CommentHistory();
        history.setHistoryId(IdGenerator.generateHistoryId());
        history.setCommentId(commentId);
        history.setActionType(actionType);
        history.setActionDescription(description);
        history.setOldStatus(oldStatus);
        history.setNewStatus(newStatus);
        history.setOldContent(oldContent);
        history.setNewContent(newContent);
        history.setOperator(operator);
        history.setOperatorType(operatorType);
        commentHistoryRepository.save(history);
    }

    private void updateCommentStats(String contentId, String commentStatus) {
        java.time.LocalDate today = java.time.LocalDate.now();
        CommentStat stat = commentStatRepository
                .findByContentIdAndStatDate(contentId, today)
                .orElse(null);

        if (stat == null) {
            stat = new CommentStat();
            stat.setStatId(IdGenerator.generateStatId());
            stat.setContentId(contentId);
            stat.setStatDate(today);
        }

        stat.setTotalComments(stat.getTotalComments() + 1);

        if ("published".equals(commentStatus)) {
            stat.setPublishedComments(stat.getPublishedComments() + 1);
        } else if ("rejected".equals(commentStatus)) {
            stat.setRejectedComments(stat.getRejectedComments() + 1);
        } else if ("pending".equals(commentStatus)) {
            stat.setPendingComments(stat.getPendingComments() + 1);
        }

        commentStatRepository.save(stat);
    }

    @Transactional
    public Map<String, Object> editComment(CommentEditRequest request) {
        Map<String, Object> result = new HashMap<>();

        Optional<Comment> commentOpt = commentRepository.findById(request.getCommentId());
        if (commentOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "评论不存在");
            return result;
        }

        Comment comment = commentOpt.get();
        String oldContent = comment.getCommentContent();
        String oldStatus = comment.getCommentStatus();

        String newContent = request.getCommentContent().trim();
        if (newContent.isEmpty()) {
            result.put("success", false);
            result.put("message", "评论内容不能为空");
            return result;
        }

        int maxLength = auditRuleManager.getMaxLength();
        if (newContent.length() > maxLength) {
            result.put("success", false);
            result.put("message", "评论内容不能超过" + maxLength + "字");
            return result;
        }

        Set<String> sensitiveWords = auditRuleManager.findSensitiveWords(newContent);
        if (!sensitiveWords.isEmpty()) {
            result.put("success", false);
            result.put("message", "评论包含敏感词: " + String.join(",", sensitiveWords));
            return result;
        }

        comment.setCommentContent(newContent);
        comment.setCommentStatus("pending");
        comment.setAuditResult("pending");
        commentRepository.save(comment);

        saveCommentHistory(request.getCommentId(), "EDIT", "评论编辑",
                oldStatus, "pending", oldContent, newContent,
                request.getUserId(), "user");

        Map<String, Object> queueResult = auditService.submitAuditTask(comment);

        AuditRecord auditRecord = new AuditRecord();
        auditRecord.setAuditId(IdGenerator.generateAuditId());
        auditRecord.setCommentId(request.getCommentId());
        auditRecord.setAuditType("auto");
        auditRecord.setAuditRules(Arrays.asList("edit_review", "queue_submit"));
        auditRecord.setAuditResult("pending");
        auditRecord.setAuditReason("评论已编辑，已重新提交审核队列");
        auditRecordRepository.save(auditRecord);

        result.put("success", true);
        result.put("comment_id", request.getCommentId());
        result.put("status", "pending");
        result.put("message", "评论已更新，正在等待重新审核");
        result.put("task_id", queueResult.get("task_id"));

        return result;
    }

    public Optional<Comment> getComment(String commentId) {
        return commentRepository.findById(commentId);
    }

    public List<Comment> getCommentsByContent(String contentId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"));
        Page<Comment> commentPage = commentRepository.findByContentIdAndCommentStatus(
                contentId, "published", pageable);
        return commentPage.getContent();
    }

    public List<Comment> getCommentsByUser(String userId) {
        return commentRepository.findByUserId(userId);
    }

    public long countCommentsByContent(String contentId) {
        return commentRepository.countByContentId(contentId);
    }

    @Transactional
    public boolean deleteComment(String commentId, String operator) {
        Optional<Comment> commentOpt = commentRepository.findById(commentId);
        if (commentOpt.isEmpty()) {
            return false;
        }

        Comment comment = commentOpt.get();
        String oldStatus = comment.getCommentStatus();
        comment.setCommentStatus("deleted");
        commentRepository.save(comment);

        saveCommentHistory(commentId, "DELETE", "评论删除",
                oldStatus, "deleted", null, null,
                operator, "admin");

        return true;
    }

    public Map<String, Object> getPublishStatus(String commentId) {
        Map<String, Object> status = new HashMap<>();

        Optional<Comment> commentOpt = commentRepository.findById(commentId);
        if (commentOpt.isEmpty()) {
            status.put("success", false);
            status.put("message", "评论不存在");
            return status;
        }

        Comment comment = commentOpt.get();
        status.put("success", true);
        status.put("comment_id", commentId);
        status.put("comment_status", comment.getCommentStatus());
        status.put("audit_result", comment.getAuditResult());

        List<AuditRecord> auditRecords = auditRecordRepository.findByCommentIdOrderByAuditedAtDesc(commentId);
        if (!auditRecords.isEmpty()) {
            AuditRecord latest = auditRecords.get(0);
            status.put("latest_audit_reason", latest.getAuditReason());
            status.put("latest_audit_type", latest.getAuditType());
        }

        return status;
    }
}
