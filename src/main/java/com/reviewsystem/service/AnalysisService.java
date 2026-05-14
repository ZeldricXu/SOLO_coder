package com.reviewsystem.service;

import com.reviewsystem.dto.CommentStatsDTO;
import com.reviewsystem.model.*;
import com.reviewsystem.repository.*;
import com.reviewsystem.util.SentimentAnalyzer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;

@Service
public class AnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(AnalysisService.class);

    @Autowired
    private CommentRepository commentRepository;

    @Autowired
    private CommentStatRepository commentStatRepository;

    @Autowired
    private SentimentAnalysisRepository sentimentAnalysisRepository;

    @Autowired
    private QualityEvaluationRepository qualityEvaluationRepository;

    @Autowired
    private ReportRecordRepository reportRecordRepository;

    @Autowired
    private ReplyRecordRepository replyRecordRepository;

    @Autowired
    private SentimentAnalyzer sentimentAnalyzer;

    public CommentStatsDTO getCommentStats(String contentId, LocalDate startDate, LocalDate endDate) {
        CommentStatsDTO stats = new CommentStatsDTO();
        stats.setContentId(contentId);

        LocalDateTime startTime = startDate != null ? startDate.atStartOfDay() : null;
        LocalDateTime endTime = endDate != null ? endDate.atTime(LocalTime.MAX) : null;

        if (startDate == null && endDate == null) {
            stats.setTotalComments((int) commentRepository.countByContentId(contentId));
            stats.setPublishedComments((int) commentRepository.countByContentIdAndCommentStatus(contentId, "published"));
            stats.setRejectedComments((int) commentRepository.countByContentIdAndCommentStatus(contentId, "rejected"));
            stats.setPendingComments((int) commentRepository.countByContentIdAndCommentStatus(contentId, "pending"));

            Double avgQuality = commentRepository.findAvgQualityScoreByContentId(contentId);
            stats.setAvgQuality(avgQuality != null ? Math.round(avgQuality * 100.0) / 100.0 : 0.0);

            Double avgSentiment = sentimentAnalysisRepository.findAvgSentimentScoreByContentId(contentId);
            stats.setAvgSentiment(avgSentiment != null ? Math.round(avgSentiment * 100.0) / 100.0 : 0.0);

            stats.setPositiveCount((int) sentimentAnalysisRepository.countByContentIdAndSentimentType(contentId, "positive"));
            stats.setNegativeCount((int) sentimentAnalysisRepository.countByContentIdAndSentimentType(contentId, "negative"));

            stats.setReportCount((int) reportRecordRepository.countByContentId(contentId));

            Long totalLikes = commentRepository.sumLikesByContentId(contentId);
            stats.setTotalLikes(totalLikes != null ? totalLikes.intValue() : 0);

            Long totalReplies = replyRecordRepository.countByContentId(contentId);
            stats.setTotalReplies(totalReplies != null ? totalReplies.intValue() : 0);
        } else {
            if (startTime == null) startTime = LocalDateTime.MIN;
            if (endTime == null) endTime = LocalDateTime.MAX;

            stats.setTotalComments((int) commentRepository.countByContentIdAndTimeRange(contentId, startTime, endTime));
            stats.setPublishedComments((int) commentRepository.countByContentIdAndStatusAndTimeRange(
                    contentId, "published", startTime, endTime));
            stats.setRejectedComments((int) commentRepository.countByContentIdAndStatusAndTimeRange(
                    contentId, "rejected", startTime, endTime));
            stats.setPendingComments((int) commentRepository.countByContentIdAndStatusAndTimeRange(
                    contentId, "pending", startTime, endTime));

            stats.setPositiveCount((int) sentimentAnalysisRepository.countByContentIdAndTypeAndTimeRange(
                    contentId, "positive", startTime, endTime));
            stats.setNegativeCount((int) sentimentAnalysisRepository.countByContentIdAndTypeAndTimeRange(
                    contentId, "negative", startTime, endTime));

            stats.setReportCount((int) reportRecordRepository.countByContentIdAndTimeRange(
                    contentId, startTime, endTime));

            List<Comment> comments = commentRepository.findByContentIdAndTimeRange(contentId, startTime, endTime);
            double totalQuality = 0;
            int qualityCount = 0;
            for (Comment c : comments) {
                if (c.getQualityScore() != null) {
                    totalQuality += c.getQualityScore();
                    qualityCount++;
                }
            }
            stats.setAvgQuality(qualityCount > 0 ? Math.round(totalQuality / qualityCount * 100.0) / 100.0 : 0.0);

            stats.setTotalLikes(0);
            stats.setTotalReplies(0);
            stats.setAvgSentiment(0.0);
        }

        logger.debug("获取评论统计: contentId={}, stats={}", contentId, stats);
        return stats;
    }

    public Map<String, Object> getSentimentAnalysis(String commentId) {
        Map<String, Object> result = new HashMap<>();

        Optional<SentimentAnalysis> existing = sentimentAnalysisRepository.findByCommentId(commentId);

        if (existing.isPresent()) {
            SentimentAnalysis analysis = existing.get();
            result.put("sentiment_id", analysis.getSentimentId());
            result.put("sentiment_type", analysis.getSentimentType());
            result.put("sentiment_score", analysis.getSentimentScore());
            result.put("positive_score", analysis.getPositiveScore());
            result.put("negative_score", analysis.getNegativeScore());
            result.put("neutral_score", analysis.getNeutralScore());
            result.put("keywords", analysis.getSentimentKeywords());
            result.put("analyzed_at", analysis.getAnalyzedAt());
        } else {
            Optional<Comment> commentOpt = commentRepository.findById(commentId);
            if (commentOpt.isPresent()) {
                Comment comment = commentOpt.get();
                SentimentAnalyzer.SentimentResult sentimentResult = sentimentAnalyzer.analyze(comment.getCommentContent());

                result.put("sentiment_type", sentimentResult.getSentimentType());
                result.put("sentiment_score", sentimentResult.getSentimentScore());
                result.put("positive_score", sentimentResult.getPositiveScore());
                result.put("negative_score", sentimentResult.getNegativeScore());
                result.put("neutral_score", sentimentResult.getNeutralScore());
                result.put("keywords", sentimentResult.getSentimentKeywords());
            } else {
                result.put("error", "评论不存在");
            }
        }

        return result;
    }

    public Map<String, Object> getQualityAnalysis(String commentId) {
        Map<String, Object> result = new HashMap<>();

        Optional<QualityEvaluation> existing = qualityEvaluationRepository.findByCommentId(commentId);

        if (existing.isPresent()) {
            QualityEvaluation evaluation = existing.get();
            result.put("evaluation_id", evaluation.getEvaluationId());
            result.put("quality_score", evaluation.getQualityScore());
            result.put("length_score", evaluation.getLengthScore());
            result.put("relevance_score", evaluation.getRelevanceScore());
            result.put("readability_score", evaluation.getReadabilityScore());
            result.put("violation_score", evaluation.getViolationScore());
            result.put("is_violation", evaluation.getViolation());
            result.put("violation_type", evaluation.getViolationType());
            result.put("violation_reason", evaluation.getViolationReason());
            result.put("spam_score", evaluation.getSpamScore());
            result.put("is_spam", evaluation.getSpam());
            result.put("evaluation_level", evaluation.getEvaluationLevel());
            result.put("evaluated_at", evaluation.getEvaluatedAt());
        } else {
            result.put("error", "未找到质量评估记录");
        }

        return result;
    }

    public List<CommentStat> getDailyStats(String contentId, LocalDate startDate, LocalDate endDate) {
        return commentStatRepository.findByContentIdAndDateRange(contentId, startDate, endDate);
    }

    public Map<String, Object> getOverallStats() {
        Map<String, Object> stats = new HashMap<>();

        long totalComments = commentRepository.count();
        long totalPublished = commentRepository.countByAuditResult("approved");
        long totalRejected = commentRepository.countByAuditResult("rejected");
        long totalPending = commentRepository.countByAuditResult("pending");

        stats.put("total_comments", totalComments);
        stats.put("total_published", totalPublished);
        stats.put("total_rejected", totalRejected);
        stats.put("total_pending", totalPending);

        long positiveCount = sentimentAnalysisRepository.countBySentimentType("positive");
        long negativeCount = sentimentAnalysisRepository.countBySentimentType("negative");
        stats.put("positive_comments", positiveCount);
        stats.put("negative_comments", negativeCount);
        stats.put("sentiment_ratio", totalPublished > 0 ?
                (double) positiveCount / totalPublished : 0.0);

        long violationCount = qualityEvaluationRepository.countByIsViolation(true);
        stats.put("violation_count", violationCount);

        long pendingReports = reportRecordRepository.countByReportStatus("pending");
        stats.put("pending_reports", pendingReports);

        return stats;
    }

    public List<Map<String, Object>> getTrendAnalysis(String contentId, int days) {
        List<Map<String, Object>> trends = new ArrayList<>();
        LocalDate endDate = LocalDate.now();
        LocalDate startDate = endDate.minusDays(days - 1);

        for (LocalDate date = startDate; !date.isAfter(endDate); date = date.plusDays(1)) {
            Map<String, Object> dailyData = new HashMap<>();
            dailyData.put("date", date.toString());

            LocalDateTime startTime = date.atStartOfDay();
            LocalDateTime endTime = date.atTime(LocalTime.MAX);

            dailyData.put("total", commentRepository.countByContentIdAndTimeRange(contentId, startTime, endTime));
            dailyData.put("published", commentRepository.countByContentIdAndStatusAndTimeRange(
                    contentId, "published", startTime, endTime));
            dailyData.put("rejected", commentRepository.countByContentIdAndStatusAndTimeRange(
                    contentId, "rejected", startTime, endTime));
            dailyData.put("positive", sentimentAnalysisRepository.countByContentIdAndTypeAndTimeRange(
                    contentId, "positive", startTime, endTime));
            dailyData.put("negative", sentimentAnalysisRepository.countByContentIdAndTypeAndTimeRange(
                    contentId, "negative", startTime, endTime));

            trends.add(dailyData);
        }

        return trends;
    }
}
