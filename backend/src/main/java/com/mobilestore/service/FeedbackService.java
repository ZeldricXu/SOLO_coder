package com.mobilestore.service;

import com.mobilestore.dto.FeedbackProcessRequest;
import com.mobilestore.dto.FeedbackSubmitRequest;
import com.mobilestore.entity.App;
import com.mobilestore.entity.Feedback;
import com.mobilestore.repository.AppRepository;
import com.mobilestore.repository.FeedbackRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class FeedbackService {

    private static final Logger logger = LoggerFactory.getLogger(FeedbackService.class);

    @Autowired
    private FeedbackRepository feedbackRepository;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private FeedbackClassificationService classificationService;

    @Autowired
    private ApprovalPermissionService permissionService;

    @Autowired
    private AsyncStatisticsService asyncStatisticsService;

    @Transactional
    public Map<String, Object> submitFeedback(FeedbackSubmitRequest request) {
        logger.info("Processing feedback submission for app: {}", request.getAppId());

        App app = appRepository.findByAppId(request.getAppId())
                .orElseThrow(() -> new IllegalArgumentException("应用不存在"));

        if (request.getContent() == null || request.getContent().trim().isEmpty()) {
            throw new IllegalArgumentException("反馈内容不能为空");
        }

        FeedbackClassificationService.ClassificationResult classification = classificationService.classify(
                request.getFeedbackType(),
                request.getContent(),
                request.getRating()
        );

        logger.info("Feedback classified - type: {}, priority: {}, assignee: {}, keywords: {}",
                classification.getFeedbackType(),
                classification.getPriority(),
                classification.getAssignee(),
                classification.getMatchedKeywords());

        Feedback feedback = new Feedback();
        feedback.setFeedbackId("fb_" + UUID.randomUUID().toString().substring(0, 8));
        feedback.setAppId(request.getAppId());
        feedback.setUserId(request.getUserId() != null ? request.getUserId() : "user_" + UUID.randomUUID().toString().substring(0, 6));
        feedback.setFeedbackType(classification.getFeedbackType());
        feedback.setContent(request.getContent());
        feedback.setRating(request.getRating());
        feedback.setStatus("pending");
        feedback.setPriority(classification.getPriority());
        feedback.setAssignee(classification.getAssignee());

        if (request.getTitle() != null) {
            feedback.setTitle(request.getTitle());
        }

        feedback = feedbackRepository.save(feedback);

        notificationService.sendNotification(
                feedback.getAssignee(),
                "feedback_new",
                "新的用户反馈",
                "应用 [" + app.getName() + "] 收到新的" + getTypeText(classification.getFeedbackType()) +
                        "，优先级: " + getPriorityText(classification.getPriority()) + "，请及时处理",
                "feedback",
                feedback.getFeedbackId()
        );

        asyncStatisticsService.invalidateCache(request.getAppId());

        Map<String, Object> result = new HashMap<>();
        result.put("feedbackId", feedback.getFeedbackId());
        result.put("status", feedback.getStatus());
        result.put("priority", feedback.getPriority());
        result.put("assignee", feedback.getAssignee());
        result.put("feedbackType", classification.getFeedbackType());
        result.put("matchedKeywords", classification.getMatchedKeywords());
        return result;
    }

    private String getTypeText(String type) {
        switch (type != null ? type.toLowerCase() : "") {
            case "bug_report":
                return "Bug反馈";
            case "feature_request":
                return "功能建议";
            case "complaint":
                return "投诉";
            case "question":
                return "咨询";
            default:
                return "反馈";
        }
    }

    private String getPriorityText(String priority) {
        switch (priority != null ? priority.toLowerCase() : "") {
            case "high":
                return "高";
            case "medium":
                return "中";
            case "low":
                return "低";
            default:
                return priority;
        }
    }

    public List<Feedback> getFeedbacks(String appId, String status, String priority) {
        if (appId != null && status != null && priority != null) {
            return feedbackRepository.findByAppIdAndStatusAndPriorityOrderByPriorityAscCreatedAtDesc(appId, status, priority);
        } else if (appId != null && status != null) {
            return feedbackRepository.findByAppIdAndStatusOrderByPriorityAscCreatedAtDesc(appId, status);
        } else if (appId != null) {
            return feedbackRepository.findByAppIdOrderByPriorityAscCreatedAtDesc(appId);
        } else if (status != null) {
            return feedbackRepository.findByStatusOrderByPriorityAscCreatedAtDesc(status);
        } else if (priority != null) {
            return feedbackRepository.findByPriorityOrderByCreatedAtDesc(priority);
        }
        return feedbackRepository.findAllByOrderByPriorityAscCreatedAtDesc();
    }

    public Feedback getFeedback(String feedbackId) {
        return feedbackRepository.findByFeedbackId(feedbackId)
                .orElseThrow(() -> new IllegalArgumentException("反馈不存在"));
    }

    @Transactional
    public Feedback processFeedback(String feedbackId, FeedbackProcessRequest request, String operator) {
        logger.info("Processing feedback: {}, operator: {}", feedbackId, operator);

        if (operator != null && !operator.isEmpty()) {
            permissionService.checkFeedbackProcessPermission(operator);
        }

        Feedback feedback = feedbackRepository.findByFeedbackId(feedbackId)
                .orElseThrow(() -> new IllegalArgumentException("反馈不存在"));

        boolean statusChanged = false;
        String oldStatus = feedback.getStatus();

        if (request.getStatus() != null) {
            if (!oldStatus.equals(request.getStatus())) {
                statusChanged = true;
            }
            feedback.setStatus(request.getStatus());
            if ("processed".equals(request.getStatus()) || "closed".equals(request.getStatus())) {
                feedback.setProcessedAt(LocalDateTime.now());
            }
        }
        if (request.getProcessingNote() != null) {
            feedback.setProcessingNote(request.getProcessingNote());
        }
        if (request.getAssignee() != null) {
            feedback.setAssignee(request.getAssignee());
        }

        feedback = feedbackRepository.save(feedback);

        if (statusChanged) {
            App app = appRepository.findByAppId(feedback.getAppId()).orElse(null);
            if (app != null) {
                notificationService.sendNotification(
                        feedback.getUserId(),
                        "feedback_updated",
                        "反馈状态更新",
                        "您关于应用 [" + app.getName() + "] 的反馈状态已更新为：" + getStatusText(feedback.getStatus()),
                        "feedback",
                        feedback.getFeedbackId()
                );
            }
            asyncStatisticsService.invalidateCache(feedback.getAppId());
        }

        return feedback;
    }

    private String getStatusText(String status) {
        switch (status != null ? status : "") {
            case "pending":
                return "待处理";
            case "processing":
                return "处理中";
            case "processed":
                return "已处理";
            case "closed":
                return "已关闭";
            default:
                return status;
        }
    }

    public Map<String, Object> getFeedbackStats(String appId) {
        Map<String, Object> stats = new HashMap<>();
        stats.put("total", feedbackRepository.countByAppId(appId));
        stats.put("pending", feedbackRepository.countByAppIdAndStatus(appId, "pending"));
        stats.put("processing", feedbackRepository.countByAppIdAndStatus(appId, "processing"));
        stats.put("processed", feedbackRepository.countByAppIdAndStatus(appId, "processed"));
        stats.put("closed", feedbackRepository.countByAppIdAndStatus(appId, "closed"));

        Map<String, Long> priorityStats = new HashMap<>();
        priorityStats.put("high", feedbackRepository.countByAppIdAndPriority(appId, "high"));
        priorityStats.put("medium", feedbackRepository.countByAppIdAndPriority(appId, "medium"));
        priorityStats.put("low", feedbackRepository.countByAppIdAndPriority(appId, "low"));
        stats.put("byPriority", priorityStats);

        Map<String, Long> typeStats = new HashMap<>();
        typeStats.put("bugReport", feedbackRepository.countByAppIdAndFeedbackType(appId, "bug_report"));
        typeStats.put("featureRequest", feedbackRepository.countByAppIdAndFeedbackType(appId, "feature_request"));
        typeStats.put("complaint", feedbackRepository.countByAppIdAndFeedbackType(appId, "complaint"));
        typeStats.put("question", feedbackRepository.countByAppIdAndFeedbackType(appId, "question"));
        typeStats.put("other", feedbackRepository.countByAppIdAndFeedbackType(appId, "other"));
        stats.put("byType", typeStats);

        return stats;
    }

    public Map<String, Object> classifyFeedbackPreview(FeedbackSubmitRequest request) {
        logger.info("Classifying feedback preview for app: {}", request.getAppId());

        FeedbackClassificationService.ClassificationResult classification = classificationService.classify(
                request.getFeedbackType(),
                request.getContent(),
                request.getRating()
        );

        Map<String, Object> result = new HashMap<>();
        result.put("feedbackType", classification.getFeedbackType());
        result.put("feedbackTypeName", getTypeText(classification.getFeedbackType()));
        result.put("priority", classification.getPriority());
        result.put("priorityName", getPriorityText(classification.getPriority()));
        result.put("assignee", classification.getAssignee());
        result.put("matchedKeywords", classification.getMatchedKeywords());
        return result;
    }

    public Map<String, List<String>> getClassificationRules() {
        return classificationService.getCategoryKeywords();
    }
}
