package com.mobilestore.test;

import com.mobilestore.entity.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public class TestDataBuilder {

    public static App buildApp(String appId, String name) {
        App app = new App();
        app.setAppId(appId);
        app.setName(name);
        app.setPlatform("android");
        app.setCategory("tools");
        app.setDeveloperId("dev_001");
        app.setStatus("active");
        app.setDescription("测试应用描述");
        app.setCreatedAt(LocalDateTime.now());
        app.setUpdatedAt(LocalDateTime.now());
        return app;
    }

    public static Version buildVersion(String versionId, String appId, String versionCode, String status) {
        Version version = new Version();
        version.setVersionId(versionId);
        version.setAppId(appId);
        version.setVersionCode(versionCode);
        version.setVersionName("V" + versionCode);
        version.setPackageUrl("http://example.com/app.apk");
        version.setReleaseNote("版本发布说明");
        version.setPublishStatus(status);
        version.setSubmitter("dev_001");
        version.setSubmittedAt(LocalDateTime.now());
        return version;
    }

    public static Version buildPendingVersion(String appId, String versionCode) {
        return buildVersion(UUID.randomUUID().toString().substring(0, 8), appId, versionCode, "pending_approval");
    }

    public static Version buildApprovedVersion(String appId, String versionCode) {
        Version version = buildVersion(UUID.randomUUID().toString().substring(0, 8), appId, versionCode, "approved");
        version.setApprover("reviewer_001");
        version.setApprovedAt(LocalDateTime.now());
        return version;
    }

    public static Feedback buildFeedback(String feedbackId, String appId, String content) {
        Feedback feedback = new Feedback();
        feedback.setFeedbackId(feedbackId);
        feedback.setAppId(appId);
        feedback.setUserId("user_001");
        feedback.setContent(content);
        feedback.setRating(3);
        feedback.setStatus("pending");
        feedback.setPriority("medium");
        feedback.setAssignee("support_001");
        feedback.setCreatedAt(LocalDateTime.now());
        return feedback;
    }

    public static Feedback buildBugFeedback(String appId, String content) {
        Feedback feedback = buildFeedback(UUID.randomUUID().toString().substring(0, 8), appId, content);
        feedback.setFeedbackType("bug_report");
        feedback.setPriority("high");
        feedback.setAssignee("tech_support_001");
        return feedback;
    }

    public static Feedback buildFeatureFeedback(String appId, String content) {
        Feedback feedback = buildFeedback(UUID.randomUUID().toString().substring(0, 8), appId, content);
        feedback.setFeedbackType("feature_request");
        feedback.setPriority("medium");
        feedback.setAssignee("product_001");
        return feedback;
    }

    public static Statistics buildStatistics(String appId, LocalDate date) {
        Statistics stats = new Statistics();
        stats.setStatId("stat_" + UUID.randomUUID().toString().substring(0, 8));
        stats.setAppId(appId);
        stats.setStatDate(date);
        stats.setDownloadCount(500L);
        stats.setActiveUsers(200L);
        stats.setAvgRating(4.2);
        stats.setFeedbackCount(10L);
        return stats;
    }

    public static Statistics buildStatistics(String appId, LocalDate date, long downloads, long activeUsers, double rating) {
        Statistics stats = new Statistics();
        stats.setStatId("stat_" + UUID.randomUUID().toString().substring(0, 8));
        stats.setAppId(appId);
        stats.setStatDate(date);
        stats.setDownloadCount(downloads);
        stats.setActiveUsers(activeUsers);
        stats.setAvgRating(rating);
        stats.setFeedbackCount(Math.round(downloads / 100));
        return stats;
    }

    public static UserRole buildUserRole(String userId, String roleCode) {
        UserRole role = new UserRole();
        role.setId("role_" + UUID.randomUUID().toString().substring(0, 8));
        role.setUserId(userId);
        role.setUserName("测试用户_" + userId);
        role.setRoleCode(roleCode);
        role.setStatus("active");
        role.setCreatedAt(LocalDateTime.now());
        return role;
    }

    public static UserRole buildReviewerRole() {
        UserRole role = buildUserRole("reviewer_001", "reviewer");
        role.setRoleName("审批人员");
        return role;
    }

    public static UserRole buildDeveloperRole() {
        UserRole role = buildUserRole("dev_001", "developer");
        role.setRoleName("开发者");
        return role;
    }

    public static UserRole buildAdminRole() {
        UserRole role = buildUserRole("admin_001", "admin");
        role.setRoleName("管理员");
        return role;
    }

    public static ApprovalLog buildApprovalLog(String versionId, String action, String operator) {
        ApprovalLog log = new ApprovalLog();
        log.setLogId("log_" + UUID.randomUUID().toString().substring(0, 8));
        log.setVersionId(versionId);
        log.setAction(action);
        log.setOperator(operator);
        log.setComment("测试审批记录");
        log.setCreatedAt(LocalDateTime.now());
        return log;
    }

    public static Notification buildNotification(String recipientId, String type) {
        Notification notification = new Notification();
        notification.setNotificationId("notif_" + UUID.randomUUID().toString().substring(0, 8));
        notification.setRecipientId(recipientId);
        notification.setType(type);
        notification.setTitle("测试通知");
        notification.setContent("测试通知内容");
        notification.setIsRead(false);
        notification.setCreatedAt(LocalDateTime.now());
        return notification;
    }
}
