package com.cms.service;

import com.cms.dto.ContentCreateRequest;
import com.cms.entity.Content;
import com.cms.entity.ContentStatistics;
import com.cms.entity.ContentTypeConfig;
import com.cms.entity.HistoryRecord;
import com.cms.exception.BusinessException;
import com.cms.repository.ContentRepository;
import com.cms.repository.ContentStatisticsRepository;
import com.cms.repository.HistoryRecordRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class ContentService {

    private static final Logger logger = LoggerFactory.getLogger(ContentService.class);

    @Autowired
    private ContentRepository contentRepository;

    @Autowired
    private ContentStatisticsRepository contentStatisticsRepository;

    @Autowired
    private HistoryRecordRepository historyRecordRepository;

    @Autowired
    private ContentTypeConfigService contentTypeConfigService;

    @Autowired
    private StatisticsQueueService statisticsQueueService;

    @Autowired
    private StatisticsWorkerService statisticsWorkerService;

    @Autowired
    private ReviewReminderService reviewReminderService;

    @Transactional
    public Content createContent(ContentCreateRequest request) {
        validateContentType(request.getContentType());

        Content content = new Content();
        String contentId = "content_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        content.setContentId(contentId);
        content.setContentTitle(request.getContentTitle());

        String contentType = request.getContentType();
        if (contentType == null || contentType.trim().isEmpty()) {
            contentType = "article";
        }
        content.setContentType(contentType);

        applyContentTypeDefaults(content, contentType);

        content.setContentBody(request.getContentBody());
        content.setContentAuthor(request.getContentAuthor());
        
        if (request.getContentCategory() != null) {
            content.setContentCategory(request.getContentCategory());
        }
        if (request.getContentTags() != null) {
            content.setContentTags(request.getContentTags());
        }
        if (request.getTemplateId() != null) {
            content.setTemplateId(request.getTemplateId());
        }

        boolean reviewRequired = contentTypeConfigService.isReviewRequired(contentType);
        content.setContentStatus(reviewRequired ? "pending_review" : "approved");

        Content savedContent = contentRepository.save(content);

        ContentStatistics statistics = new ContentStatistics();
        statistics.setStatId("stat_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        statistics.setContentId(contentId);
        statistics.setViewCount(0L);
        statistics.setLikeCount(0L);
        statistics.setCommentCount(0L);
        statistics.setShareCount(0L);
        contentStatisticsRepository.save(statistics);

        recordHistory(contentId, "create", "创建内容", request.getContentAuthor(), null, content.getContentStatus());

        logger.info("创建内容: contentId={}, contentType={}, status={}", 
            contentId, contentType, content.getContentStatus());

        return savedContent;
    }

    private void validateContentType(String contentType) {
        if (contentType != null && !contentType.trim().isEmpty()) {
            if (!contentTypeConfigService.isContentTypeValid(contentType)) {
                throw new BusinessException(400, "无效的内容类型: " + contentType);
            }
        }
    }

    private void applyContentTypeDefaults(Content content, String contentType) {
        ContentTypeConfig config = contentTypeConfigService.getActiveConfigByCode(contentType);
        
        if (config != null) {
            if (config.getDefaultCategory() != null && content.getContentCategory() == null) {
                content.setContentCategory(config.getDefaultCategory());
            }
            if (config.getDefaultTemplateId() != null && content.getTemplateId() == null) {
                content.setTemplateId(config.getDefaultTemplateId());
            }
        }
    }

    @Transactional
    public Content updateContent(String contentId, ContentCreateRequest request) {
        Content content = getContentById(contentId);

        if ("published".equals(content.getContentStatus())) {
            throw new BusinessException(400, "已发布内容不可编辑");
        }

        if (request.getContentType() != null) {
            validateContentType(request.getContentType());
            content.setContentType(request.getContentType());
        }

        content.setContentTitle(request.getContentTitle());
        content.setContentBody(request.getContentBody());
        if (request.getContentAuthor() != null) {
            content.setContentAuthor(request.getContentAuthor());
        }
        if (request.getContentCategory() != null) {
            content.setContentCategory(request.getContentCategory());
        }
        if (request.getContentTags() != null) {
            content.setContentTags(request.getContentTags());
        }
        if (request.getTemplateId() != null) {
            content.setTemplateId(request.getTemplateId());
        }

        Content updatedContent = contentRepository.save(content);

        recordHistory(contentId, "update", "更新内容", request.getContentAuthor(), 
            content.getContentStatus(), content.getContentStatus());

        return updatedContent;
    }

    public Content getContentById(String contentId) {
        Optional<Content> contentOpt = contentRepository.findById(contentId);
        if (!contentOpt.isPresent()) {
            throw new BusinessException(404, "内容不存在");
        }
        return contentOpt.get();
    }

    public List<Content> getAllContents() {
        return contentRepository.findAll();
    }

    public List<Content> getContentsByStatus(String status) {
        return contentRepository.findByContentStatus(status);
    }

    public List<Content> getContentsByCategory(String category) {
        return contentRepository.findByContentCategory(category);
    }

    public List<Content> getContentsByAuthor(String author) {
        return contentRepository.findByContentAuthor(author);
    }

    @Transactional
    public void deleteContent(String contentId) {
        Content content = getContentById(contentId);
        if ("published".equals(content.getContentStatus())) {
            throw new BusinessException(400, "已发布内容不可删除");
        }

        reviewReminderService.cancelRemindersByContentId(contentId);
        contentRepository.delete(content);

        logger.info("删除内容: contentId={}", contentId);
    }

    @Transactional
    public Content submitForReview(String contentId) {
        Content content = getContentById(contentId);
        String previousStatus = content.getContentStatus();

        if ("pending_review".equals(previousStatus) || "approved".equals(previousStatus) || "published".equals(previousStatus)) {
            throw new BusinessException(400, "内容状态不允许提交审核");
        }

        boolean reviewRequired = contentTypeConfigService.isReviewRequired(content.getContentType());
        if (!reviewRequired) {
            content.setContentStatus("approved");
            logger.info("内容类型无需审核，直接标记为已通过: contentId={}", contentId);
        } else {
            content.setContentStatus("pending_review");
        }

        Content updatedContent = contentRepository.save(content);

        recordHistory(contentId, "submit_review", "提交审核", content.getContentAuthor(), previousStatus, content.getContentStatus());

        if ("pending_review".equals(content.getContentStatus())) {
            reviewReminderService.createReminderAsync(contentId, "reviewer_default", "默认审核员");
            logger.info("创建审核提醒: contentId={}", contentId);
        }

        return updatedContent;
    }

    public void recordViewAsync(String contentId) {
        Content content = getContentById(contentId);
        if (!"published".equals(content.getContentStatus())) {
            throw new BusinessException(400, "未发布内容不可阅读");
        }

        String taskId = statisticsQueueService.enqueueViewTask(contentId, null, null);
        
        logger.debug("入队阅读统计任务: contentId={}, taskId={}", contentId, taskId);
    }

    @Async
    @Transactional
    public void recordView(String contentId) {
        try {
            statisticsWorkerService.updateViewCount(contentId);
        } catch (Exception e) {
            logger.error("记录阅读统计失败: contentId={}", contentId, e);
        }
    }

    public void recordLikeAsync(String contentId) {
        Content content = getContentById(contentId);
        if (!"published".equals(content.getContentStatus())) {
            throw new BusinessException(400, "未发布内容不可点赞");
        }

        String taskId = statisticsQueueService.enqueueLikeTask(contentId, null, null);
        
        logger.debug("入队点赞统计任务: contentId={}, taskId={}", contentId, taskId);
    }

    @Async
    @Transactional
    public void recordLike(String contentId) {
        try {
            statisticsWorkerService.updateLikeCount(contentId);
        } catch (Exception e) {
            logger.error("记录点赞统计失败: contentId={}", contentId, e);
        }
    }

    public void recordShareAsync(String contentId) {
        Content content = getContentById(contentId);
        if (!"published".equals(content.getContentStatus())) {
            throw new BusinessException(400, "未发布内容不可分享");
        }

        String taskId = statisticsQueueService.enqueueShareTask(contentId, null, null);
        
        logger.debug("入队分享统计任务: contentId={}, taskId={}", contentId, taskId);
    }

    @Async
    @Transactional
    public void recordShare(String contentId) {
        try {
            statisticsWorkerService.updateShareCount(contentId);
        } catch (Exception e) {
            logger.error("记录分享统计失败: contentId={}", contentId, e);
        }
    }

    @Transactional
    public Content updateStatus(String contentId, String newStatus, String operatorId) {
        Content content = getContentById(contentId);
        String previousStatus = content.getContentStatus();
        content.setContentStatus(newStatus);
        Content updatedContent = contentRepository.save(content);

        String operationType = "status_update";
        String operationName = "更新状态";
        if ("approved".equals(newStatus) || "rejected".equals(newStatus)) {
            operationType = "review";
            operationName = "审核";
        } else if ("published".equals(newStatus)) {
            operationType = "publish";
            operationName = "发布";
        } else if ("unpublished".equals(newStatus)) {
            operationType = "unpublish";
            operationName = "下架";
        }

        recordHistory(contentId, operationType, operationName, operatorId, previousStatus, newStatus);

        if ("approved".equals(newStatus) || "rejected".equals(newStatus) || "published".equals(newStatus)) {
            reviewReminderService.cancelRemindersByContentId(contentId);
        }

        return updatedContent;
    }

    private void recordHistory(String contentId, String operationType, String operationName, String operatorId, String previousStatus, String newStatus) {
        HistoryRecord history = new HistoryRecord();
        history.setHistoryId("history_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        history.setContentId(contentId);
        history.setOperationType(operationType);
        history.setOperationName(operationName);
        history.setOperatorId(operatorId);
        history.setOperationTime(LocalDateTime.now());
        history.setPreviousStatus(previousStatus);
        history.setNewStatus(newStatus);
        historyRecordRepository.save(history);
    }

    public String generateId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    public String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) {
            return null;
        }
        return dateTime.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    public long getPendingStatisticsTasks() {
        return statisticsWorkerService.getTotalPendingTasks();
    }

    public long getPendingViewTasks() {
        return statisticsWorkerService.getPendingViewTasks();
    }

    public long getPendingLikeTasks() {
        return statisticsWorkerService.getPendingLikeTasks();
    }

    public long getPendingShareTasks() {
        return statisticsWorkerService.getPendingShareTasks();
    }
}
