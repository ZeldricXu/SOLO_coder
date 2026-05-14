package com.reviewsystem.service;

import com.reviewsystem.model.Comment;
import com.reviewsystem.model.QualityEvaluation;
import com.reviewsystem.model.SentimentAnalysis;
import com.reviewsystem.repository.CommentRepository;
import com.reviewsystem.repository.QualityEvaluationRepository;
import com.reviewsystem.repository.SentimentAnalysisRepository;
import com.reviewsystem.util.IdGenerator;
import com.reviewsystem.util.QualityEvaluator;
import com.reviewsystem.util.SentimentAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class EvaluationService {

    private static final Logger logger = LoggerFactory.getLogger(EvaluationService.class);

    @Autowired
    private QualityEvaluationRepository qualityEvaluationRepository;

    @Autowired
    private SentimentAnalysisRepository sentimentAnalysisRepository;

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private QualityEvaluator qualityEvaluator;

    @Autowired
    private SentimentAnalyzer sentimentAnalyzer;

    @Autowired
    private HistoryService historyService;

    @Transactional
    public Map<String, Object> evaluateComment(String commentId) {
        Map<String, Object> result = new HashMap<>();

        Optional<Comment> commentOpt = commentRepository.findById(commentId);
        if (commentOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "评论不存在");
            return result;
        }

        Comment comment = commentOpt.get();
        String content = comment.getCommentContent();

        QualityEvaluator.QualityResult qualityResult = qualityEvaluator.evaluate(content);

        QualityEvaluation evaluation = saveQualityEvaluation(commentId, qualityResult);

        SentimentAnalyzer.SentimentResult sentimentResult = sentimentAnalyzer.analyze(content);
        SentimentAnalysis sentiment = saveSentimentAnalysis(commentId, sentimentResult);

        comment.setQualityScore(qualityResult.getQualityScore());
        commentRepository.save(comment);

        if (qualityResult.isViolation()) {
            historyService.recordHistory(commentId, "VIOLATION_DETECTED",
                    "检测到违规内容: " + qualityResult.getViolationReason(),
                    comment.getCommentStatus(), comment.getCommentStatus(),
                    null, null, "system", "system");

            result.put("is_violation", true);
            result.put("violation_type", qualityResult.getViolationType());
            result.put("violation_reason", qualityResult.getViolationReason());
        } else {
            result.put("is_violation", false);
        }

        result.put("success", true);
        result.put("evaluation_id", evaluation.getEvaluationId());
        result.put("comment_id", commentId);
        result.put("quality_score", qualityResult.getQualityScore());
        result.put("length_score", qualityResult.getLengthScore());
        result.put("relevance_score", qualityResult.getRelevanceScore());
        result.put("readability_score", qualityResult.getReadabilityScore());
        result.put("violation_score", qualityResult.getViolationScore());
        result.put("is_spam", qualityResult.isSpam());
        result.put("spam_score", qualityResult.isSpam() ? 80 : 0);
        result.put("evaluation_level", qualityResult.getEvaluationLevel());

        result.put("sentiment_type", sentimentResult.getSentimentType());
        result.put("sentiment_score", sentimentResult.getSentimentScore());
        result.put("sentiment_keywords", sentimentResult.getSentimentKeywords());

        logger.info("评论质量评估完成: commentId={}, score={}, level={}",
                commentId, qualityResult.getQualityScore(), qualityResult.getEvaluationLevel());
        return result;
    }

    private QualityEvaluation saveQualityEvaluation(String commentId, QualityEvaluator.QualityResult qualityResult) {
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

        return qualityEvaluationRepository.save(evaluation);
    }

    private SentimentAnalysis saveSentimentAnalysis(String commentId, SentimentAnalyzer.SentimentResult sentimentResult) {
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

        return sentimentAnalysisRepository.save(analysis);
    }

    public Map<String, Object> getEvaluationResult(String commentId) {
        Map<String, Object> result = new HashMap<>();

        Optional<QualityEvaluation> qualityOpt = qualityEvaluationRepository.findByCommentId(commentId);
        if (qualityOpt.isPresent()) {
            QualityEvaluation eval = qualityOpt.get();
            result.put("quality", Map.of(
                    "evaluation_id", eval.getEvaluationId(),
                    "quality_score", eval.getQualityScore(),
                    "length_score", eval.getLengthScore(),
                    "relevance_score", eval.getRelevanceScore(),
                    "readability_score", eval.getReadabilityScore(),
                    "violation_score", eval.getViolationScore(),
                    "is_violation", eval.getViolation(),
                    "violation_type", eval.getViolationType(),
                    "violation_reason", eval.getViolationReason(),
                    "is_spam", eval.getSpam(),
                    "spam_score", eval.getSpamScore(),
                    "evaluation_level", eval.getEvaluationLevel()
            ));
        }

        Optional<SentimentAnalysis> sentimentOpt = sentimentAnalysisRepository.findByCommentId(commentId);
        if (sentimentOpt.isPresent()) {
            SentimentAnalysis sentiment = sentimentOpt.get();
            result.put("sentiment", Map.of(
                    "sentiment_id", sentiment.getSentimentId(),
                    "sentiment_type", sentiment.getSentimentType(),
                    "sentiment_score", sentiment.getSentimentScore(),
                    "positive_score", sentiment.getPositiveScore(),
                    "negative_score", sentiment.getNegativeScore(),
                    "neutral_score", sentiment.getNeutralScore(),
                    "keywords", sentiment.getSentimentKeywords()
            ));
        }

        return result;
    }

    public List<QualityEvaluation> getViolationList(int limit) {
        List<QualityEvaluation> violations = qualityEvaluationRepository.findByIsViolation(true);
        return violations.size() > limit ? violations.subList(0, limit) : violations;
    }

    public List<QualityEvaluation> getSpamList(int limit) {
        List<QualityEvaluation> spams = qualityEvaluationRepository.findByIsSpam(true);
        return spams.size() > limit ? spams.subList(0, limit) : spams;
    }

    public Map<String, Long> getViolationStats() {
        Map<String, Long> stats = new HashMap<>();
        stats.put("total_violations", qualityEvaluationRepository.countByIsViolation(true));
        stats.put("total_spam", qualityEvaluationRepository.countByViolationType("spam"));
        stats.put("excellent", qualityEvaluationRepository.countByEvaluationLevel("excellent"));
        stats.put("good", qualityEvaluationRepository.countByEvaluationLevel("good"));
        stats.put("medium", qualityEvaluationRepository.countByEvaluationLevel("medium"));
        stats.put("low", qualityEvaluationRepository.countByEvaluationLevel("low"));
        stats.put("poor", qualityEvaluationRepository.countByEvaluationLevel("poor"));
        return stats;
    }

    @Transactional
    public Map<String, Object> handleViolation(String commentId, String handler, String action, String note) {
        Map<String, Object> result = new HashMap<>();

        Optional<Comment> commentOpt = commentRepository.findById(commentId);
        if (commentOpt.isEmpty()) {
            result.put("success", false);
            result.put("message", "评论不存在");
            return result;
        }

        Comment comment = commentOpt.get();
        String oldStatus = comment.getCommentStatus();

        if ("delete".equalsIgnoreCase(action)) {
            comment.setCommentStatus("deleted");
            commentRepository.save(comment);
            historyService.recordHistory(commentId, "VIOLATION_DELETE",
                    "违规评论已删除: " + note,
                    oldStatus, "deleted", null, null, handler, "admin");
        } else if ("warning".equalsIgnoreCase(action)) {
            historyService.recordHistory(commentId, "VIOLATION_WARNING",
                    "违规警告: " + note,
                    oldStatus, oldStatus, null, null, handler, "admin");
        } else if ("ignore".equalsIgnoreCase(action)) {
            Optional<QualityEvaluation> evalOpt = qualityEvaluationRepository.findByCommentId(commentId);
            if (evalOpt.isPresent()) {
                QualityEvaluation eval = evalOpt.get();
                eval.setViolation(false);
                eval.setViolationType(null);
                eval.setViolationReason(null);
                qualityEvaluationRepository.save(eval);
            }
            historyService.recordHistory(commentId, "VIOLATION_IGNORE",
                    "忽略违规标记: " + note,
                    oldStatus, oldStatus, null, null, handler, "admin");
        } else {
            result.put("success", false);
            result.put("message", "无效的处理操作");
            return result;
        }

        result.put("success", true);
        result.put("comment_id", commentId);
        result.put("action", action);
        result.put("handler", handler);
        result.put("note", note);

        logger.info("违规处理完成: commentId={}, action={}, handler={}", commentId, action, handler);
        return result;
    }

    @Transactional
    public void batchEvaluate(List<String> commentIds) {
        for (String commentId : commentIds) {
            evaluateComment(commentId);
        }
        logger.info("批量评估完成: count={}", commentIds.size());
    }
}
