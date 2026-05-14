package com.cms.builder;

import com.cms.dto.CommentCreateRequest;
import com.cms.dto.ContentCreateRequest;
import com.cms.dto.PublishExecuteRequest;
import com.cms.dto.ReviewProcessRequest;
import com.cms.entity.*;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class TestDataBuilder {

    private static final String[] CONTENT_STATUSES = {"draft", "pending_review", "approved", "published", "unpublished", "rejected"};
    private static final String[] PUBLISH_CHANNELS = {"web", "mobile", "api", "email"};
    private static final String[] REVIEW_URGENCY_LEVELS = {"normal", "high", "urgent", "critical"};

    public static ContentCreateRequest buildContentCreateRequest() {
        ContentCreateRequest request = new ContentCreateRequest();
        request.setContentTitle("测试内容标题");
        request.setContentType("article");
        request.setContentBody("这是测试内容的正文内容，包含完整的文章信息。");
        request.setContentAuthor("测试编辑");
        request.setContentCategory("tech");
        request.setContentTags(Arrays.asList("技术", "编程"));
        return request;
    }

    public static ContentCreateRequest buildContentCreateRequest(String title, String type, String category) {
        ContentCreateRequest request = new ContentCreateRequest();
        request.setContentTitle(title);
        request.setContentType(type);
        request.setContentBody("这是" + title + "的正文内容。");
        request.setContentAuthor("测试编辑");
        request.setContentCategory(category);
        request.setContentTags(Arrays.asList("技术", "编程"));
        return request;
    }

    public static Content buildContent() {
        Content content = new Content();
        content.setContentId("content_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        content.setContentTitle("测试内容标题");
        content.setContentType("article");
        content.setContentBody("这是测试内容的正文内容。");
        content.setContentStatus("pending_review");
        content.setContentAuthor("测试编辑");
        content.setContentCategory("tech");
        content.setContentTags(Arrays.asList("技术", "编程"));
        return content;
    }

    public static Content buildContentWithStatus(String status) {
        Content content = buildContent();
        content.setContentStatus(status);
        return content;
    }

    public static Content buildDraftContent() {
        Content content = buildContent();
        content.setContentStatus("draft");
        return content;
    }

    public static Content buildPendingReviewContent() {
        Content content = buildContent();
        content.setContentStatus("pending_review");
        return content;
    }

    public static Content buildApprovedContent() {
        Content content = buildContent();
        content.setContentStatus("approved");
        content.setReviewerId("reviewer_001");
        content.setReviewComment("审核通过");
        content.setReviewedAt(LocalDateTime.now());
        return content;
    }

    public static Content buildPublishedContent() {
        Content content = buildApprovedContent();
        content.setContentStatus("published");
        content.setPublishedAt(LocalDateTime.now());
        return content;
    }

    public static Content buildUnpublishedContent() {
        Content content = buildPublishedContent();
        content.setContentStatus("unpublished");
        content.setUnpublishedAt(LocalDateTime.now());
        return content;
    }

    public static Content buildRejectedContent() {
        Content content = buildContent();
        content.setContentStatus("rejected");
        content.setReviewerId("reviewer_001");
        content.setReviewComment("内容不符合要求");
        content.setReviewedAt(LocalDateTime.now());
        return content;
    }

    public static Content buildContentWithConfig(String templateId, String category, List<String> tags) {
        Content content = buildContent();
        content.setTemplateId(templateId);
        content.setContentCategory(category);
        content.setContentTags(tags);
        return content;
    }

    public static List<Content> buildContentList(int count) {
        List<Content> contents = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            Content content = buildContent();
            content.setContentTitle("测试内容标题_" + (i + 1));
            contents.add(content);
        }
        return contents;
    }

    public static ReviewProcessRequest buildReviewProcessRequest(String contentId) {
        ReviewProcessRequest request = new ReviewProcessRequest();
        request.setContentId(contentId);
        request.setReviewStatus("approved");
        request.setReviewComment("审核通过，内容合规。");
        request.setReviewerId("reviewer_001");
        request.setReviewerName("审核员");
        return request;
    }

    public static ReviewProcessRequest buildReviewProcessRequest(String contentId, String status, String reviewerId, String reviewerName) {
        ReviewProcessRequest request = new ReviewProcessRequest();
        request.setContentId(contentId);
        request.setReviewStatus(status);
        request.setReviewComment(status.equals("approved") ? "审核通过，内容合规。" : "审核拒绝，内容存在问题。");
        request.setReviewerId(reviewerId);
        request.setReviewerName(reviewerName);
        return request;
    }

    public static ReviewProcessRequest buildRejectReviewRequest(String contentId) {
        ReviewProcessRequest request = new ReviewProcessRequest();
        request.setContentId(contentId);
        request.setReviewStatus("rejected");
        request.setReviewComment("审核拒绝，内容存在问题。");
        request.setReviewerId("reviewer_001");
        request.setReviewerName("审核员");
        return request;
    }

    public static ReviewProcessRequest buildReviewWithUrgency(String contentId, String urgencyLevel) {
        ReviewProcessRequest request = buildReviewProcessRequest(contentId);
        request.setReviewComment("【" + urgencyLevel + "】" + request.getReviewComment());
        return request;
    }

    public static ReviewRecord buildReviewRecord(String contentId) {
        ReviewRecord record = new ReviewRecord();
        record.setReviewId("review_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        record.setContentId(contentId);
        record.setReviewerId("reviewer_001");
        record.setReviewerName("审核员");
        record.setReviewStatus("approved");
        record.setReviewComment("审核通过");
        record.setReviewTime(LocalDateTime.now());
        return record;
    }

    public static ReviewRecord buildReviewRecord(String contentId, String status, String reviewerId, String reviewerName) {
        ReviewRecord record = buildReviewRecord(contentId);
        record.setReviewStatus(status);
        record.setReviewerId(reviewerId);
        record.setReviewerName(reviewerName);
        record.setReviewComment(status.equals("approved") ? "审核通过" : "审核拒绝");
        return record;
    }

    public static ReviewRecord buildApprovedReviewRecord(String contentId) {
        return buildReviewRecord(contentId, "approved", "reviewer_001", "审核员A");
    }

    public static ReviewRecord buildRejectedReviewRecord(String contentId) {
        return buildReviewRecord(contentId, "rejected", "reviewer_002", "审核员B");
    }

    public static ReviewRecord buildUrgentReviewRecord(String contentId, String status) {
        ReviewRecord record = buildReviewRecord(contentId, status, "reviewer_senior", "高级审核员");
        record.setReviewComment("【紧急】" + record.getReviewComment());
        return record;
    }

    public static List<ReviewRecord> buildReviewRecordList(String contentId, int count) {
        List<ReviewRecord> records = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            ReviewRecord record = buildReviewRecord(contentId);
            record.setReviewComment("审核记录_" + (i + 1));
            records.add(record);
        }
        return records;
    }

    public static PublishExecuteRequest buildPublishExecuteRequest(String contentId) {
        PublishExecuteRequest request = new PublishExecuteRequest();
        request.setContentId(contentId);
        request.setPublishChannel("web");
        Map<String, String> config = new HashMap<>();
        config.put("schedule", "immediate");
        request.setPublishConfig(config);
        request.setPublisherId("publisher_001");
        request.setPublisherName("发布员");
        return request;
    }

    public static PublishExecuteRequest buildPublishExecuteRequest(String contentId, String channel, boolean immediate) {
        PublishExecuteRequest request = new PublishExecuteRequest();
        request.setContentId(contentId);
        request.setPublishChannel(channel);
        Map<String, String> config = new HashMap<>();
        config.put("schedule", immediate ? "immediate" : "scheduled");
        request.setPublishConfig(config);
        if (!immediate) {
            request.setScheduleTime(LocalDateTime.now().plusHours(24));
        }
        request.setPublisherId("publisher_001");
        request.setPublisherName("发布员");
        return request;
    }

    public static PublishExecuteRequest buildWebPublishRequest(String contentId) {
        return buildPublishExecuteRequest(contentId, "web", true);
    }

    public static PublishExecuteRequest buildMobilePublishRequest(String contentId) {
        return buildPublishExecuteRequest(contentId, "mobile", true);
    }

    public static PublishExecuteRequest buildScheduledPublishRequest(String contentId, LocalDateTime scheduleTime) {
        PublishExecuteRequest request = buildPublishExecuteRequest(contentId, "web", false);
        request.setScheduleTime(scheduleTime);
        return request;
    }

    public static PublishExecuteRequest buildScheduledPublishRequest(String contentId) {
        return buildScheduledPublishRequest(contentId, LocalDateTime.now().plusHours(24));
    }

    public static PublishRecord buildPublishRecord(String contentId) {
        PublishRecord record = new PublishRecord();
        record.setPublishId("publish_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        record.setContentId(contentId);
        record.setPublishChannel("web");
        record.setPublishTime(LocalDateTime.now());
        record.setPublishStatus("published");
        record.setPublisherId("publisher_001");
        record.setPublisherName("发布员");
        return record;
    }

    public static PublishRecord buildPublishRecord(String contentId, String channel, String status) {
        PublishRecord record = buildPublishRecord(contentId);
        record.setPublishChannel(channel);
        record.setPublishStatus(status);
        return record;
    }

    public static PublishRecord buildWebPublishRecord(String contentId) {
        return buildPublishRecord(contentId, "web", "published");
    }

    public static PublishRecord buildMobilePublishRecord(String contentId) {
        return buildPublishRecord(contentId, "mobile", "published");
    }

    public static PublishRecord buildScheduledPublishRecord(String contentId) {
        PublishRecord record = buildPublishRecord(contentId, "web", "scheduled");
        record.setScheduleTime(LocalDateTime.now().plusHours(24));
        return record;
    }

    public static PublishRecord buildUnpublishRecord(String contentId) {
        PublishRecord record = buildPublishRecord(contentId, "web", "unpublished");
        Map<String, String> config = new HashMap<>();
        config.put("action", "unpublish");
        record.setPublishConfig(config);
        return record;
    }

    public static List<PublishRecord> buildPublishRecordList(String contentId, int count) {
        List<PublishRecord> records = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            records.add(buildPublishRecord(contentId));
        }
        return records;
    }

    public static CommentCreateRequest buildCommentCreateRequest(String contentId) {
        CommentCreateRequest request = new CommentCreateRequest();
        request.setContentId(contentId);
        request.setCommentContent("这是一条测试评论");
        request.setUserId("user_001");
        request.setUserName("测试用户");
        return request;
    }

    public static Comment buildComment(String contentId) {
        Comment comment = new Comment();
        comment.setCommentId("comment_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        comment.setContentId(contentId);
        comment.setUserId("user_001");
        comment.setUserName("测试用户");
        comment.setCommentContent("这是一条测试评论");
        comment.setCommentTime(LocalDateTime.now());
        comment.setCommentStatus("active");
        return comment;
    }

    public static Comment buildReplyComment(String contentId, String parentCommentId) {
        Comment comment = buildComment(contentId);
        comment.setParentCommentId(parentCommentId);
        comment.setCommentContent("这是一条回复评论");
        return comment;
    }

    public static Category buildCategory() {
        Category category = new Category();
        category.setCategoryId("category_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        category.setCategoryName("技术分类");
        category.setCategoryType("tech");
        category.setCategoryDescription("技术相关内容分类");
        category.setCategoryStatus("active");
        return category;
    }

    public static Category buildCategory(String name, String type) {
        Category category = buildCategory();
        category.setCategoryName(name);
        category.setCategoryType(type);
        return category;
    }

    public static Tag buildTag() {
        Tag tag = new Tag();
        tag.setTagId("tag_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        tag.setTagName("Java技术");
        tag.setTagType("programming");
        tag.setTagColor("#007bff");
        tag.setTagStatus("active");
        return tag;
    }

    public static Tag buildTag(String name, String type) {
        Tag tag = buildTag();
        tag.setTagName(name);
        tag.setTagType(type);
        return tag;
    }

    public static Template buildTemplate() {
        Template template = new Template();
        template.setTemplateId("template_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        template.setTemplateName("标准文章模板");
        template.setTemplateType("article");
        template.setTemplateContent("<div>{{content}}</div>");
        template.setTemplateStatus("active");
        template.setCreatedBy("admin");
        return template;
    }

    public static ContentStatistics buildContentStatistics(String contentId) {
        ContentStatistics statistics = new ContentStatistics();
        statistics.setStatId("stat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        statistics.setContentId(contentId);
        statistics.setViewCount(100L);
        statistics.setLikeCount(20L);
        statistics.setCommentCount(10L);
        statistics.setShareCount(5L);
        return statistics;
    }

    public static ContentStatistics buildEmptyContentStatistics(String contentId) {
        ContentStatistics statistics = buildContentStatistics(contentId);
        statistics.setViewCount(0L);
        statistics.setLikeCount(0L);
        statistics.setCommentCount(0L);
        statistics.setShareCount(0L);
        return statistics;
    }

    public static ContentStatistics buildContentStatisticsWithCounts(String contentId, long views, long likes, long comments, long shares) {
        ContentStatistics statistics = buildContentStatistics(contentId);
        statistics.setViewCount(views);
        statistics.setLikeCount(likes);
        statistics.setCommentCount(comments);
        statistics.setShareCount(shares);
        return statistics;
    }

    public static MonthlyStatistics buildMonthlyStatistics() {
        MonthlyStatistics statistics = new MonthlyStatistics();
        statistics.setStatId("mstat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        statistics.setStatMonth("2026-05");
        statistics.setContentCount(100L);
        statistics.setPublishCount(80L);
        statistics.setReviewCount(90L);
        statistics.setRejectCount(10L);
        statistics.setTotalView(50000L);
        statistics.setTotalComment(5000L);
        statistics.setTotalLike(10000L);
        statistics.setTotalShare(2000L);
        return statistics;
    }

    public static MonthlyStatistics buildMonthlyStatistics(String month) {
        MonthlyStatistics statistics = buildMonthlyStatistics();
        statistics.setStatMonth(month);
        return statistics;
    }

    public static HistoryRecord buildHistoryRecord(String contentId) {
        HistoryRecord history = new HistoryRecord();
        history.setHistoryId("history_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        history.setContentId(contentId);
        history.setOperationType("create");
        history.setOperationName("创建内容");
        history.setOperatorId("editor_001");
        history.setOperatorName("编辑员");
        history.setOperationTime(LocalDateTime.now());
        history.setPreviousStatus(null);
        history.setNewStatus("pending_review");
        return history;
    }

    public static HistoryRecord buildHistoryRecord(String contentId, String operationType, String previousStatus, String newStatus) {
        HistoryRecord history = buildHistoryRecord(contentId);
        history.setOperationType(operationType);
        history.setPreviousStatus(previousStatus);
        history.setNewStatus(newStatus);
        
        switch (operationType) {
            case "create":
                history.setOperationName("创建内容");
                break;
            case "submit_review":
                history.setOperationName("提交审核");
                break;
            case "review":
                history.setOperationName(newStatus.equals("approved") ? "审核通过" : "审核拒绝");
                break;
            case "publish":
                history.setOperationName("发布内容");
                break;
            case "unpublish":
                history.setOperationName("下架内容");
                break;
            case "update":
                history.setOperationName("更新内容");
                break;
            default:
                history.setOperationName(operationType);
        }
        return history;
    }

    public static HistoryRecord buildCreateHistory(String contentId) {
        return buildHistoryRecord(contentId, "create", null, "pending_review");
    }

    public static HistoryRecord buildSubmitReviewHistory(String contentId) {
        return buildHistoryRecord(contentId, "submit_review", "draft", "pending_review");
    }

    public static HistoryRecord buildApprovedHistory(String contentId) {
        return buildHistoryRecord(contentId, "review", "pending_review", "approved");
    }

    public static HistoryRecord buildRejectedHistory(String contentId) {
        return buildHistoryRecord(contentId, "review", "pending_review", "rejected");
    }

    public static HistoryRecord buildPublishHistory(String contentId) {
        return buildHistoryRecord(contentId, "publish", "approved", "published");
    }

    public static HistoryRecord buildUnpublishHistory(String contentId) {
        return buildHistoryRecord(contentId, "unpublish", "published", "unpublished");
    }

    public static List<HistoryRecord> buildContentLifecycleHistory(String contentId) {
        List<HistoryRecord> history = new java.util.ArrayList<>();
        history.add(buildCreateHistory(contentId));
        history.add(buildSubmitReviewHistory(contentId));
        history.add(buildApprovedHistory(contentId));
        history.add(buildPublishHistory(contentId));
        return history;
    }

    public static String[] getContentStatuses() {
        return CONTENT_STATUSES.clone();
    }

    public static String[] getPublishChannels() {
        return PUBLISH_CHANNELS.clone();
    }

    public static String[] getReviewUrgencyLevels() {
        return REVIEW_URGENCY_LEVELS.clone();
    }
}
