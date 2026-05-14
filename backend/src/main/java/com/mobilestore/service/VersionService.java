package com.mobilestore.service;

import com.mobilestore.dto.VersionPublishRequest;
import com.mobilestore.entity.App;
import com.mobilestore.entity.ApprovalLog;
import com.mobilestore.entity.Version;
import com.mobilestore.repository.AppRepository;
import com.mobilestore.repository.ApprovalLogRepository;
import com.mobilestore.repository.VersionRepository;
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
import java.util.regex.Pattern;

@Service
public class VersionService {

    private static final Logger logger = LoggerFactory.getLogger(VersionService.class);

    @Autowired
    private VersionRepository versionRepository;

    @Autowired
    private AppRepository appRepository;

    @Autowired
    private ApprovalLogRepository approvalLogRepository;

    @Autowired
    private NotificationService notificationService;

    @Autowired
    private ApprovalPermissionService approvalPermissionService;

    private static final Pattern VERSION_PATTERN = Pattern.compile("^\\d+(\\.\\d+){0,2}$");

    @Transactional
    public Map<String, Object> publishVersion(VersionPublishRequest request) {
        logger.info("Processing version publish request for app: {}", request.getAppId());

        String submitter = request.getSubmitter() != null ? request.getSubmitter() : "dev_001";
        approvalPermissionService.checkSubmitPermission(submitter);

        App app = appRepository.findByAppId(request.getAppId())
                .orElseThrow(() -> new IllegalArgumentException("应用不存在"));

        if (!"active".equals(app.getStatus()) && !"draft".equals(app.getStatus())) {
            throw new IllegalArgumentException("应用状态异常，无法提交发布");
        }

        if (!VERSION_PATTERN.matcher(request.getVersionCode()).matches()) {
            throw new IllegalArgumentException("版本号格式不正确，应为如 1.0.0 格式");
        }

        if (versionRepository.existsByAppIdAndVersionCode(request.getAppId(), request.getVersionCode())) {
            throw new IllegalArgumentException("该版本号已存在");
        }

        Version version = new Version();
        version.setVersionId("ver_" + UUID.randomUUID().toString().substring(0, 8));
        version.setAppId(request.getAppId());
        version.setVersionCode(request.getVersionCode());
        version.setVersionName(request.getVersionName() != null ? request.getVersionName() : "V" + request.getVersionCode());
        version.setPackageUrl(request.getPackageUrl());
        version.setReleaseNote(request.getReleaseNote());
        version.setPublishStatus("pending_approval");
        version.setSubmitter(submitter);

        version = versionRepository.save(version);

        ApprovalLog log = new ApprovalLog();
        log.setLogId("log_" + UUID.randomUUID().toString().substring(0, 8));
        log.setVersionId(version.getVersionId());
        log.setAction("submit");
        log.setOperator(version.getSubmitter());
        log.setComment("版本提交审批，提交人: " + submitter);
        approvalLogRepository.save(log);

        List<com.mobilestore.entity.UserRole> reviewers = approvalPermissionService.getAllReviewers();
        for (com.mobilestore.entity.UserRole reviewer : reviewers) {
            notificationService.sendNotification(
                    reviewer.getUserId(),
                    "approval_pending",
                    "新版本待审批",
                    "应用 [" + app.getName() + "] 版本 " + version.getVersionCode() + " 已提交审批，请及时处理",
                    "version",
                    version.getVersionId()
            );
        }

        logger.info("Version published successfully: {}, status: pending_approval", version.getVersionId());

        Map<String, Object> result = new HashMap<>();
        result.put("versionId", version.getVersionId());
        result.put("status", version.getPublishStatus());
        result.put("versionCode", version.getVersionCode());
        result.put("submitter", submitter);
        return result;
    }

    @Transactional
    public Map<String, Object> processApproval(String versionId, String result, String comment, String approver) {
        logger.info("Processing approval for version: {}, result: {}, approver: {}", versionId, result, approver);

        String actualApprover = approver != null ? approver : "reviewer_001";

        if ("approved".equalsIgnoreCase(result)) {
            approvalPermissionService.checkApprovalPermission(actualApprover);
        } else if ("rejected".equalsIgnoreCase(result)) {
            approvalPermissionService.checkRejectPermission(actualApprover);
        }

        Version version = versionRepository.findByVersionId(versionId)
                .orElseThrow(() -> new IllegalArgumentException("版本记录不存在"));

        if (!"pending_approval".equals(version.getPublishStatus())) {
            throw new IllegalArgumentException("当前状态不允许审批，当前状态: " + version.getPublishStatus());
        }

        App app = appRepository.findByAppId(version.getAppId())
                .orElseThrow(() -> new IllegalArgumentException("关联应用不存在"));

        if ("approved".equalsIgnoreCase(result)) {
            version.setPublishStatus("approved");
            version.setApprovedAt(LocalDateTime.now());
            version.setApprover(actualApprover);

            app.setStatus("active");
            appRepository.save(app);

            ApprovalLog log = new ApprovalLog();
            log.setLogId("log_" + UUID.randomUUID().toString().substring(0, 8));
            log.setVersionId(version.getVersionId());
            log.setAction("approve");
            log.setOperator(actualApprover);
            log.setComment(comment != null ? comment : "审批通过");
            approvalLogRepository.save(log);

            notificationService.sendNotification(
                    version.getSubmitter(),
                    "publish_approved",
                    "版本发布成功",
                    "应用 [" + app.getName() + "] 版本 " + version.getVersionCode() + " 已成功发布",
                    "version",
                    version.getVersionId()
            );

            logger.info("Version approved: {} by {}", versionId, actualApprover);
        } else if ("rejected".equalsIgnoreCase(result)) {
            version.setPublishStatus("rejected");
            version.setRejectReason(comment);

            ApprovalLog log = new ApprovalLog();
            log.setLogId("log_" + UUID.randomUUID().toString().substring(0, 8));
            log.setVersionId(version.getVersionId());
            log.setAction("reject");
            log.setOperator(actualApprover);
            log.setComment(comment != null ? comment : "审批拒绝");
            approvalLogRepository.save(log);

            notificationService.sendNotification(
                    version.getSubmitter(),
                    "publish_rejected",
                    "版本发布被拒绝",
                    "应用 [" + app.getName() + "] 版本 " + version.getVersionCode() + " 发布被拒绝，原因：" + comment,
                    "version",
                    version.getVersionId()
            );

            logger.info("Version rejected: {} by {}", versionId, actualApprover);
        } else {
            throw new IllegalArgumentException("无效的审批结果，应为 approved 或 rejected");
        }

        version = versionRepository.save(version);

        Map<String, Object> response = new HashMap<>();
        response.put("versionId", version.getVersionId());
        response.put("status", version.getPublishStatus());
        response.put("approvedAt", version.getApprovedAt());
        response.put("approver", version.getApprover());
        return response;
    }

    public List<Version> getVersions(String appId, String status) {
        if (appId != null && status != null) {
            return versionRepository.findByAppIdAndPublishStatusOrderBySubmittedAtDesc(appId, status);
        } else if (appId != null) {
            return versionRepository.findByAppIdOrderBySubmittedAtDesc(appId);
        } else if (status != null) {
            return versionRepository.findByPublishStatusOrderBySubmittedAtDesc(status);
        }
        return versionRepository.findAll();
    }

    public Version getVersion(String versionId) {
        return versionRepository.findByVersionId(versionId)
                .orElseThrow(() -> new IllegalArgumentException("版本不存在"));
    }

    public List<ApprovalLog> getApprovalLogs(String versionId) {
        return approvalLogRepository.findByVersionIdOrderByCreatedAtDesc(versionId);
    }

    public Map<String, Object> checkApprovalPermission(String userId) {
        Map<String, Object> result = new HashMap<>();
        boolean hasPermission = approvalPermissionService.isReviewer(userId);
        String role = approvalPermissionService.getUserRole(userId);
        result.put("hasPermission", hasPermission);
        result.put("role", role);
        result.put("userId", userId);
        return result;
    }
}
