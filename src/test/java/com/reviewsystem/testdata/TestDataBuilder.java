package com.reviewsystem.testdata;

import com.reviewsystem.dto.CommentEditRequest;
import com.reviewsystem.dto.CommentPublishRequest;
import com.reviewsystem.dto.ReportRequest;
import com.reviewsystem.dto.ReplyRequest;
import com.reviewsystem.model.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class TestDataBuilder {

    public static final String TEST_USER_ID = "user_10086";
    public static final String TEST_CONTENT_ID = "article_001";
    public static final String TEST_COMMENT_ID = "comment_test_001";
    public static final String TEST_COMMENT_ID_2 = "comment_test_002";
    public static final String TEST_COMMENT_ID_3 = "comment_test_003";
    public static final String TEST_AUDITOR = "admin_user";
    public static final String TEST_REPORT_USER = "user_report_001";

    public static final String NORMAL_COMMENT = "这篇文章写得很好，内容详实，逻辑清晰，非常值得一读。推荐给所有感兴趣的朋友。";
    public static final String SENSITIVE_COMMENT = "这篇文章写得很好，但里面有些赌博内容不太合适。";
    public static final String SHORT_COMMENT = "好";
    public static final String EMPTY_COMMENT = "   ";
    public static final String LONG_COMMENT = "这是一篇很长的评论".repeat(100);
    public static final String SPAM_COMMENT = "加微信13800138000获取优惠券";
    public static final String NEGATIVE_COMMENT = "这篇文章写得很差，内容空洞，逻辑混乱，不值得阅读。";
    public static final String POSITIVE_COMMENT = "太棒了！非常优秀的作品，作者很用心，受益匪浅！";
    public static final String NEUTRAL_COMMENT = "这篇文章讨论了一个技术问题，内容比较客观。";

    public static final Set<String> SENSITIVE_WORDS = Set.of("赌博", "色情", "暴力", "毒品", "诈骗");

    public static final Map<String, Integer> REPORT_PRIORITY = Map.of(
            "spam", 30,
            "violation", 50,
            "abuse", 70,
            "illegal", 90
    );

    public static final List<String> AUDIT_RULES = Arrays.asList(
            "sensitive_word", "quality_check", "spam_check"
    );

    public static CommentPublishRequest buildNormalPublishRequest() {
        return buildPublishRequest(TEST_USER_ID, TEST_CONTENT_ID, NORMAL_COMMENT);
    }

    public static CommentPublishRequest buildSensitivePublishRequest() {
        return buildPublishRequest(TEST_USER_ID, TEST_CONTENT_ID, SENSITIVE_COMMENT);
    }

    public static CommentPublishRequest buildShortPublishRequest() {
        return buildPublishRequest(TEST_USER_ID, TEST_CONTENT_ID, SHORT_COMMENT);
    }

    public static CommentPublishRequest buildEmptyPublishRequest() {
        return buildPublishRequest(TEST_USER_ID, TEST_CONTENT_ID, EMPTY_COMMENT);
    }

    public static CommentPublishRequest buildLongPublishRequest() {
        return buildPublishRequest(TEST_USER_ID, TEST_CONTENT_ID, LONG_COMMENT);
    }

    public static CommentPublishRequest buildSpamPublishRequest() {
        return buildPublishRequest(TEST_USER_ID, TEST_CONTENT_ID, SPAM_COMMENT);
    }

    public static CommentPublishRequest buildPublishRequest(String userId, String contentId, String content) {
        CommentPublishRequest request = new CommentPublishRequest();
        request.setUserId(userId);
        request.setContentId(contentId);
        request.setCommentContent(content);
        request.setCommentType("text");
        return request;
    }

    public static CommentEditRequest buildEditRequest(String commentId, String newContent) {
        return buildEditRequest(commentId, TEST_USER_ID, newContent);
    }

    public static CommentEditRequest buildEditRequest(String commentId, String userId, String newContent) {
        CommentEditRequest request = new CommentEditRequest();
        request.setCommentId(commentId);
        request.setUserId(userId);
        request.setCommentContent(newContent);
        return request;
    }

    public static ReportRequest buildSpamReportRequest(String commentId) {
        return buildReportRequest(commentId, "spam", "垃圾广告评论", TEST_REPORT_USER);
    }

    public static ReportRequest buildViolationReportRequest(String commentId) {
        return buildReportRequest(commentId, "violation", "违规内容", TEST_REPORT_USER);
    }

    public static ReportRequest buildReportRequest(String commentId, String reportType,
                                                   String reportReason, String reportUserId) {
        ReportRequest request = new ReportRequest();
        request.setCommentId(commentId);
        request.setReportType(reportType);
        request.setReportReason(reportReason);
        request.setReportUserId(reportUserId);
        return request;
    }

    public static ReplyRequest buildReplyRequest(String commentId, String replyUser, String replyContent) {
        ReplyRequest request = new ReplyRequest();
        request.setCommentId(commentId);
        request.setReplyUser(replyUser);
        request.setReplyContent(replyContent);
        return request;
    }

    public static Comment buildComment(String commentId, String content, String status,
                                       Integer qualityScore, Integer recommendScore) {
        Comment comment = new Comment();
        comment.setCommentId(commentId);
        comment.setContentId(TEST_CONTENT_ID);
        comment.setUserId(TEST_USER_ID);
        comment.setCommentContent(content);
        comment.setCommentType("text");
        comment.setCommentStatus(status);
        comment.setAuditResult("approved".equals(status) ? "approved" : status);
        comment.setQualityScore(qualityScore);
        comment.setRecommendScore(recommendScore);
        comment.setLikeCount(0);
        comment.setReplyCount(0);
        comment.setCreatedAt(LocalDateTime.now());
        comment.setUpdatedAt(LocalDateTime.now());
        return comment;
    }

    public static Comment buildPublishedComment(String commentId, String content,
                                                int qualityScore, int recommendScore) {
        return buildComment(commentId, content, "published", qualityScore, recommendScore);
    }

    public static Comment buildPendingComment(String commentId, String content) {
        return buildComment(commentId, content, "pending", null, null);
    }

    public static Comment buildHighQualityComment(String commentId) {
        Comment comment = buildPublishedComment(commentId, POSITIVE_COMMENT, 90, 85);
        comment.setLikeCount(100);
        comment.setReplyCount(20);
        return comment;
    }

    public static Comment buildMediumQualityComment(String commentId) {
        Comment comment = buildPublishedComment(commentId, NEUTRAL_COMMENT, 70, 65);
        comment.setLikeCount(30);
        comment.setReplyCount(5);
        return comment;
    }

    public static Comment buildLowQualityComment(String commentId) {
        Comment comment = buildPublishedComment(commentId, SHORT_COMMENT, 35, 40);
        comment.setLikeCount(5);
        comment.setReplyCount(1);
        return comment;
    }

    public static Comment buildOldComment(String commentId, LocalDateTime createdAt) {
        Comment comment = buildPublishedComment(commentId, NORMAL_COMMENT, 80, 75);
        comment.setCreatedAt(createdAt);
        comment.setLikeCount(50);
        return comment;
    }

    public static AuditRecord buildAuditRecord(String auditId, String commentId,
                                               String auditType, String auditResult) {
        return buildAuditRecord(auditId, commentId, auditType, auditResult, null);
    }

    public static AuditRecord buildAuditRecord(String auditId, String commentId,
                                               String auditType, String auditResult,
                                               String auditReason) {
        AuditRecord record = new AuditRecord();
        record.setAuditId(auditId);
        record.setCommentId(commentId);
        record.setAuditType(auditType);
        record.setAuditRules(AUDIT_RULES);
        record.setAuditResult(auditResult);
        record.setAuditReason(auditReason);
        record.setQualityScore(80);
        record.setAuditedAt(LocalDateTime.now());
        return record;
    }

    public static SentimentAnalysis buildSentimentAnalysis(String commentId, String sentimentType,
                                                           double sentimentScore) {
        SentimentAnalysis analysis = new SentimentAnalysis();
        analysis.setSentimentId("sentiment_" + commentId);
        analysis.setCommentId(commentId);
        analysis.setSentimentType(sentimentType);
        analysis.setSentimentScore(sentimentScore);
        analysis.setPositiveScore(sentimentType.equals("positive") ? sentimentScore : 0.2);
        analysis.setNegativeScore(sentimentType.equals("negative") ? sentimentScore : 0.1);
        analysis.setNeutralScore(sentimentType.equals("neutral") ? sentimentScore : 0.2);
        analysis.setSentimentKeywords(sentimentType.equals("positive") ?
                Arrays.asList("好", "优秀", "棒") : sentimentType.equals("negative") ?
                Arrays.asList("差", "烂", "糟") : Arrays.asList("客观", "讨论"));
        analysis.setAnalyzedAt(LocalDateTime.now());
        return analysis;
    }

    public static RecommendRecord buildRecommendRecord(String recommendId, String commentId,
                                                       int recommendScore, int position) {
        RecommendRecord record = new RecommendRecord();
        record.setRecommendId(recommendId);
        record.setCommentId(commentId);
        record.setContentId(TEST_CONTENT_ID);
        record.setRecommendType("quality");
        record.setRecommendScore(recommendScore);
        record.setQualityFactor(85);
        record.setHeatFactor(60);
        record.setTimeFactor(90);
        record.setSentimentFactor(75);
        record.setRecommendPosition(position);
        record.setCalculatedAt(LocalDateTime.now());
        return record;
    }

    public static ReportRecord buildReportRecord(String reportId, String commentId,
                                                 String reportType, String status) {
        ReportRecord record = new ReportRecord();
        record.setReportId(reportId);
        record.setCommentId(commentId);
        record.setReportUserId(TEST_REPORT_USER);
        record.setReportType(reportType);
        record.setReportReason("测试举报原因");
        record.setReportStatus(status);
        record.setPriority(REPORT_PRIORITY.getOrDefault(reportType, 50));
        record.setReportedAt(LocalDateTime.now());
        return record;
    }

    public static ReportRecord buildPendingReport(String reportId, String commentId, String reportType) {
        return buildReportRecord(reportId, commentId, reportType, "pending");
    }

    public static QualityEvaluation buildQualityEvaluation(String evalId, String commentId,
                                                           int qualityScore, boolean isViolation) {
        QualityEvaluation eval = new QualityEvaluation();
        eval.setEvaluationId(evalId);
        eval.setCommentId(commentId);
        eval.setQualityScore(qualityScore);
        eval.setLengthScore(Math.min(100, qualityScore + 5));
        eval.setRelevanceScore(Math.min(100, qualityScore - 5));
        eval.setReadabilityScore(Math.min(100, qualityScore));
        eval.setViolationScore(isViolation ? 80 : 10);
        eval.setViolation(isViolation);
        eval.setViolationType(isViolation ? "spam" : null);
        eval.setViolationReason(isViolation ? "检测到垃圾内容" : null);
        eval.setSpamScore(isViolation ? 85 : 5);
        eval.setSpam(isViolation);
        eval.setEvaluationLevel(qualityScore >= 80 ? "excellent" :
                qualityScore >= 60 ? "good" : qualityScore >= 40 ? "average" : "poor");
        return eval;
    }

    public static CommentHistory buildCommentHistory(String historyId, String commentId,
                                                     String actionType, String operator) {
        CommentHistory history = new CommentHistory();
        history.setHistoryId(historyId);
        history.setCommentId(commentId);
        history.setActionType(actionType);
        history.setActionDescription("测试操作: " + actionType);
        history.setOldStatus("published");
        history.setNewStatus("published");
        history.setOperator(operator);
        history.setOperatorType(operator.equals(TEST_USER_ID) ? "user" : "admin");
        history.setCreatedAt(LocalDateTime.now());
        return history;
    }

    public static ReplyRecord buildReplyRecord(String replyId, String commentId, String replyUser) {
        ReplyRecord reply = new ReplyRecord();
        reply.setReplyId(replyId);
        reply.setCommentId(commentId);
        reply.setReplyUser(replyUser);
        reply.setReplyContent("这是一条测试回复");
        reply.setLikeCount(0);
        reply.setReplyTime(LocalDateTime.now());
        return reply;
    }

    public static CommentStat buildCommentStat(String statId, String contentId,
                                               int total, int published, int rejected, int pending) {
        CommentStat stat = new CommentStat();
        stat.setStatId(statId);
        stat.setContentId(contentId);
        stat.setStatDate(java.time.LocalDate.now());
        stat.setTotalComments(total);
        stat.setPublishedComments(published);
        stat.setRejectedComments(rejected);
        stat.setPendingComments(pending);
        stat.setAvgQualityScore(75);
        return stat;
    }
}
