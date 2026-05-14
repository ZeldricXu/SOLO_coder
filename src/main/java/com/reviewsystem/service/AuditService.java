package com.reviewsystem.service;

import com.reviewsystem.config.AuditRuleConfig;
import com.reviewsystem.config.QueueConfig;
import com.reviewsystem.model.AuditRecord;
import com.reviewsystem.model.Comment;
import com.reviewsystem.model.QualityEvaluation;
import com.reviewsystem.model.SentimentAnalysis;
import com.reviewsystem.queue.AuditTask;
import com.reviewsystem.queue.RedisQueueService;
import com.reviewsystem.repository.AuditRecordRepository;
import com.reviewsystem.repository.CommentHistoryRepository;
import com.reviewsystem.repository.CommentRepository;
import com.reviewsystem.repository.QualityEvaluationRepository;
import com.reviewsystem.repository.SentimentAnalysisRepository;
import com.reviewsystem.rule.AuditRuleManager;
import com.reviewsystem.util.IdGenerator;
import com.reviewsystem.util.QualityEvaluator;
import com.reviewsystem.util.SentimentAnalyzer;
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
public class AuditService {

    private static final Logger logger = LoggerFactory.getLogger(AuditService.class);

    @Autowired
    private AuditRecordRepository auditRecordRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private QualityEvaluationRepository qualityEvaluationRepository;

    @Autowired
    private SentimentAnalysisRepository sentimentAnalysisRepository;

    @Autowired
    private CommentHistoryRepository commentHistoryRepository;

    @Autowired
    private QualityEvaluator qualityEvaluator;

    @Autowired
    private SentimentAnalyzer sentimentAnalyzer;

    @Autowired
    private RecommendService recommendService;

    @Autowired
    private HistoryService historyService;

    @Resource
    private AuditRuleManager auditRuleManager;

    @Resource
    private RedisQueueService redisQueueService;

    @Resource
    private QueueConfig queueConfig;

    public Map<String, Object> submitAuditTask(Comment comment) {
        Map<String, Object> result = new HashMap<>();

        AuditTask task = new AuditTask();
        task.setTaskId(IdGenerator.generateAuditId());
        task.setCommentId(comment.getCommentId());
        task.setUserId(comment.getUserId());
        task.setContentId(comment.getContentId());
        task.setContent(comment.getCommentContent());

        String queueName = queueConfig.getAudit().getName();
        boolean submitted = redisQueueService.pushTask(queueName, task);

        if (submitted) {
            result.put("success", true);
            result.put("task_id", task.getTaskId());
            result.put("comment_id", comment.getCommentId());
            result.put("message", "审核任务已提交，队列位置: " + redisQueueService.getQueueSize(queueName));
            logger.info("审核任务已入队: commentId={}, taskId={}", comment.getCommentId(), task.getTaskId());
        } else {
            result.put("success", false);
            result.put("message", "审核任务提交失败");
            logger.error("审核任务入队失败: commentId={}", comment.getCommentId());
        }

        return result;
    }

    @Transactional
    public void executeAudit(AuditTask task) {
        String commentId = task.getCommentId();
        Optional<Comment> commentOpt = commentRepository.findById(commentId);
        if (commentOpt.isEmpty()) {
            logger.warn("执行审核时评论不存在: {}", commentId);
            return;
        }

        Comment comment = commentOpt.get();
        String content = comment.getCommentContent();

        List<String> auditRules = Arrays.asList("sensitive_word", "quality_check", "spam_check");

        Set<String> matchedWords = auditRuleManager.findSensitiveWords(content);
        if (!matchedWords.isEmpty()) {
            createAuditResult(comment, "rejected", "rejected",
                    "评论包含敏感词: " + String.join(",", matchedWords),
                    String.join(",", matchedWords), auditRules, null, null);
            return;
        }

        if (auditRuleManager.isSpamContent(content)) {
            createAuditResult(comment, "rejected", "rejected",
                    "评论包含广告或联系方式，判定为垃圾内容",
                    null, auditRules, null, null);
            return;
        }

        if (content.length() < auditRuleManager.getMinLength() ||
                content.length() > auditRuleManager.getMaxLength()) {
            createAuditResult(comment, "pending", "pending",
                    "评论长度不规范，建议人工审核", null, auditRules, null, null);
            return;
        }

        QualityEvaluator.QualityResult qualityResult = qualityEvaluator.evaluate(content);
        saveQualityEvaluation(commentId, qualityResult);

        if (qualityResult.isViolation()) {
            createAuditResult(comment, "rejected", "rejected",
                    qualityResult.getViolationReason(), null, auditRules, null, qualityResult);
            return;
        }

        if (qualityResult.getQualityScore() < auditRuleManager.getMinQualityScore()) {
            createAuditResult(comment, "pending", "pending",
                    "评论质量较低，建议人工审核", null, auditRules, null, qualityResult);
            return;
        }

        SentimentAnalyzer.SentimentResult sentimentResult = sentimentAnalyzer.analyze(content);
        saveSentimentAnalysis(commentId, sentimentResult);

        comment.setQualityScore(qualityResult.getQualityScore());
        comment.setAuditResult("approved");
        comment.setCommentStatus("published");

        int recommendScore = recommendService.calculateRecommendScore(
                qualityResult.getQualityScore(), sentimentResult.getSentimentScore(),
                comment.getContentId() != null ? comment.getContentId() : "default");
        comment.setRecommendScore(recommendScore);
        commentRepository.save(comment);

        recommendService.createRecommendRecord(comment, recommendScore,
                qualityResult.getQualityScore(), sentimentResult.getSentimentScore());

        historyService.recordHistory(commentId, "AUDIT", "评论审核通过",
                "pending", "published", null, null, "system", "system");

        AuditRecord record = new AuditRecord();
        record.setAuditId(IdGenerator.generateAuditId());
        record.setCommentId(commentId);
        record.setAuditType("auto");
        record.setAuditRules(auditRules);
        record.setAuditResult("approved");
        record.setAuditReason("审核通过");
        record.setQualityScore(qualityResult.getQualityScore());
        auditRecordRepository.save(record);

        logger.info("自动审核完成: commentId={}, result={}", commentId, "approved");
    }

    @Transactional
    public Map<String, Object> auditComment(String commentId, String auditor) {
        Map<String, Object> result = new HashMap<>();

        Optional<Comment> commentOpt = commentRepository.findById(commentId);
        if (commentOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "评论不存在");
            return result;
        }

        Comment comment = commentOpt.get();
        String content = comment.getCommentContent();

        List<String> auditRules = Arrays.asList("sensitive_word", "quality_check", "spam_check");

        Set<String> matchedWords = auditRuleManager.findSensitiveWords(content);
        if (!matchedWords.isEmpty()) {
            return createAuditResult(comment, "rejected", "rejected",
                    "评论包含敏感词: " + String.join(",", matchedWords),
                    String.join(",", matchedWords), auditRules, auditor, null);
        }

        if (auditRuleManager.isSpamContent(content)) {
            return createAuditResult(comment, "rejected", "rejected",
                    "评论包含广告或联系方式，判定为垃圾内容",
                    null, auditRules, auditor, null);
        }

        QualityEvaluator.QualityResult qualityResult = qualityEvaluator.evaluate(content);

        saveQualityEvaluation(commentId, qualityResult);

        if (qualityResult.isViolation()) {
            return createAuditResult(comment, "rejected", "rejected",
                    qualityResult.getViolationReason(), null, auditRules, auditor, qualityResult);
        }

        if (qualityResult.getQualityScore() < auditRuleManager.getMinQualityScore()) {
            return createAuditResult(comment, "pending", "pending",
                    "评论质量较低，建议人工审核", null, auditRules, auditor, qualityResult);
        }

        SentimentAnalyzer.SentimentResult sentimentResult = sentimentAnalyzer.analyze(content);
        saveSentimentAnalysis(commentId, sentimentResult);

        comment.setQualityScore(qualityResult.getQualityScore());
        comment.setAuditResult("approved");
        comment.setCommentStatus("published");

        int recommendScore = recommendService.calculateRecommendScore(
                qualityResult.getQualityScore(), sentimentResult.getSentimentScore(),
                comment.getContentId() != null ? comment.getContentId() : "default");
        comment.setRecommendScore(recommendScore);
        commentRepository.save(comment);

        recommendService.createRecommendRecord(comment, recommendScore,
                qualityResult.getQualityScore(), sentimentResult.getSentimentScore());

        historyService.recordHistory(commentId, "AUDIT", "评论审核通过",
                "pending", "published", null, null, auditor, "admin");

        AuditRecord record = new AuditRecord();
        record.setAuditId(IdGenerator.generateAuditId());
        record.setCommentId(commentId);
        record.setAuditType(auditor != null ? "manual" : "auto");
        record.setAuditRules(auditRules);
        record.setAuditResult("approved");
        record.setAuditReason("审核通过");
        record.setQualityScore(qualityResult.getQualityScore());
        auditRecordRepository.save(record);

        result.put("success", true);
        result.put("audit_id", record.getAuditId());
        result.put("comment_id", commentId);
        result.put("audit_result", "approved");
        result.put("comment_status", "published");
        result.put("quality_score", qualityResult.getQualityScore());
        result.put("sentiment_type", sentimentResult.getSentimentType());
        result.put("sentiment_score", sentimentResult.getSentimentScore());

        logger.info("评论审核完成: commentId={}, result={}", commentId, "approved");
        return result;
    }

    private Map<String, Object> createAuditResult(Comment comment, String auditResult,
                                                   String commentStatus, String reason,
                                                   String sensitiveWords, List<String> auditRules,
                                                   String auditor, QualityEvaluator.QualityResult qualityResult) {
        Map<String, Object> result = new HashMap<>();

        AuditRecord record = new AuditRecord();
        record.setAuditId(IdGenerator.generateAuditId());
        record.setCommentId(comment.getCommentId());
        record.setAuditType(auditor != null ? "manual" : "auto");
        record.setAuditRules(auditRules);
        record.setAuditResult(auditResult);
        record.setAuditReason(reason);
        record.setSensitiveWords(sensitiveWords);
        if (qualityResult != null) {
            record.setQualityScore(qualityResult.getQualityScore());
        }
        auditRecordRepository.save(record);

        comment.setAuditResult(auditResult);
        comment.setCommentStatus(commentStatus);
        commentRepository.save(comment);

        historyService.recordHistory(comment.getCommentId(), "AUDIT",
                "评论" + ("approved".equals(auditResult) ? "审核通过" : "审核拒绝"),
                null, commentStatus, null, null,
                auditor != null ? auditor : "system", auditor != null ? "admin" : "system");

        result.put("success", true);
        result.put("audit_id", record.getAuditId());
        result.put("comment_id", comment.getCommentId());
        result.put("audit_result", auditResult);
        result.put("comment_status", commentStatus);
        if (reason != null) {
            result.put("audit_reason", reason);
        }

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

    @Transactional
    public Map<String, Object> manualAudit(String commentId, String auditor,
                                            String decision, String reason) {
        Map<String, Object> result = new HashMap<>();

        Optional<Comment> commentOpt = commentRepository.findById(commentId);
        if (commentOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "评论不存在");
            return result;
        }

        Comment comment = commentOpt.get();
        String oldStatus = comment.getCommentStatus();
        String auditResult;
        String commentStatus;

        if ("approve".equalsIgnoreCase(decision)) {
            auditResult = "approved";
            commentStatus = "published";

            QualityEvaluator.QualityResult qualityResult = qualityEvaluator.evaluate(comment.getCommentContent());
            saveQualityEvaluation(commentId, qualityResult);
            comment.setQualityScore(qualityResult.getQualityScore());

            SentimentAnalyzer.SentimentResult sentimentResult = sentimentAnalyzer.analyze(comment.getCommentContent());
            saveSentimentAnalysis(commentId, sentimentResult);

            int recommendScore = recommendService.calculateRecommendScore(
                    qualityResult.getQualityScore(), sentimentResult.getSentimentScore(),
                    comment.getContentId() != null ? comment.getContentId() : "default");
            comment.setRecommendScore(recommendScore);

            recommendService.createRecommendRecord(comment, recommendScore,
                    qualityResult.getQualityScore(), sentimentResult.getSentimentScore());

            result.put("quality_score", qualityResult.getQualityScore());
            result.put("sentiment_type", sentimentResult.getSentimentType());
        } else if ("reject".equalsIgnoreCase(decision)) {
            auditResult = "rejected";
            commentStatus = "rejected";
        } else {
            result.put("success", false);
            result.put("message", "无效的审核决策");
            return result;
        }

        AuditRecord record = new AuditRecord();
        record.setAuditId(IdGenerator.generateAuditId());
        record.setCommentId(commentId);
        record.setAuditType("manual");
        record.setAuditRules(Arrays.asList("manual_audit"));
        record.setAuditResult(auditResult);
        record.setAuditReason(reason);
        auditRecordRepository.save(record);

        comment.setAuditResult(auditResult);
        comment.setCommentStatus(commentStatus);
        commentRepository.save(comment);

        historyService.recordHistory(commentId, "MANUAL_AUDIT",
                "人工审核: " + ("approve".equalsIgnoreCase(decision) ? "通过" : "拒绝"),
                oldStatus, commentStatus, null, null, auditor, "admin");

        result.put("success", true);
        result.put("audit_id", record.getAuditId());
        result.put("comment_id", commentId);
        result.put("audit_result", auditResult);
        result.put("comment_status", commentStatus);
        result.put("auditor", auditor);

        logger.info("人工审核完成: commentId={}, decision={}, auditor={}",
                commentId, decision, auditor);
        return result;
    }

    public List<AuditRecord> getAuditRecords(String commentId) {
        return auditRecordRepository.findByCommentIdOrderByAuditedAtDesc(commentId);
    }

    public List<Comment> getPendingComments(int page, int size) {
        Pageable pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "createdAt"));
        Page<Comment> commentPage = commentRepository.findByCommentStatus("pending", pageable);
        return commentPage.getContent();
    }

    public long countPendingComments() {
        return commentRepository.countByAuditResult("pending");
    }

    public Map<String, Long> getAuditStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("approved", auditRecordRepository.countByAuditResult("approved"));
        stats.put("rejected", auditRecordRepository.countByAuditResult("rejected"));
        stats.put("pending", commentRepository.countByAuditResult("pending"));
        stats.put("queue_size", redisQueueService.getQueueSize(queueConfig.getAudit().getName()));
        return stats;
    }

    public Map<String, Object> getQueueStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("pending_queue", redisQueueService.getQueueSize(queueConfig.getAudit().getName()));
        status.put("processing_queue", redisQueueService.getQueueSize(queueConfig.getAudit().getName() + ":processing"));
        status.put("dead_queue", redisQueueService.getQueueSize(queueConfig.getAudit().getName() + ":dead"));
        return status;
    }
}
