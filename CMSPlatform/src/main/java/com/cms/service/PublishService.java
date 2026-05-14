package com.cms.service;

import com.cms.dto.PublishExecuteRequest;
import com.cms.entity.Content;
import com.cms.entity.HistoryRecord;
import com.cms.entity.PublishRecord;
import com.cms.exception.BusinessException;
import com.cms.repository.HistoryRecordRepository;
import com.cms.repository.PublishRecordRepository;
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
public class PublishService {

    private static final Logger logger = LoggerFactory.getLogger(PublishService.class);

    @Autowired
    private PublishRecordRepository publishRecordRepository;

    @Autowired
    private ContentService contentService;

    @Autowired
    private HistoryRecordRepository historyRecordRepository;

    @Autowired
    private PublishWarningService publishWarningService;

    @Autowired
    private ContentTypeConfigService contentTypeConfigService;

    @Transactional
    public PublishRecord executePublish(PublishExecuteRequest request) {
        Content content = contentService.getContentById(request.getContentId());

        validateContentStatusForPublish(content);

        String publishChannel = request.getPublishChannel() != null ? request.getPublishChannel() : "web";
        LocalDateTime publishTime = request.getScheduleTime() != null ? request.getScheduleTime() : LocalDateTime.now();

        Map<String, String> publishConfig = request.getPublishConfig() != null ? request.getPublishConfig() : new HashMap<>();
        if (!publishConfig.containsKey("schedule")) {
            publishConfig.put("schedule", request.getScheduleTime() != null ? "scheduled" : "immediate");
        }

        boolean isScheduled = request.getScheduleTime() != null && request.getScheduleTime().isAfter(LocalDateTime.now());

        PublishRecord publishRecord = new PublishRecord();
        publishRecord.setPublishId("publish_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        publishRecord.setContentId(request.getContentId());
        publishRecord.setPublishChannel(publishChannel);
        publishRecord.setPublishTime(isScheduled ? null : publishTime);
        publishRecord.setPublishStatus(isScheduled ? "scheduled" : "published");
        publishRecord.setScheduleTime(request.getScheduleTime());
        publishRecord.setPublishConfig(publishConfig);
        publishRecord.setPublisherId(request.getPublisherId());
        publishRecord.setPublisherName(request.getPublisherName());

        PublishRecord savedRecord = publishRecordRepository.save(publishRecord);

        if (!isScheduled) {
            content.setContentStatus("published");
            content.setPublishedAt(publishTime);
            contentService.updateStatus(request.getContentId(), "published", request.getPublisherId());
        }

        recordPublishHistory(content, publishRecord, request);

        if (isScheduled) {
            publishWarningService.createWarningAsync(
                request.getContentId(),
                savedRecord.getPublishId(),
                request.getScheduleTime(),
                request.getPublisherId(),
                request.getPublisherName(),
                publishChannel
            );
            logger.info("创建定时发布预警: contentId={}, publishId={}, scheduleTime={}", 
                request.getContentId(), savedRecord.getPublishId(), request.getScheduleTime());
        }

        logger.info("执行内容发布: contentId={}, status={}, channel={}, isScheduled={}", 
            request.getContentId(), publishRecord.getPublishStatus(), publishChannel, isScheduled);

        return savedRecord;
    }

    private void validateContentStatusForPublish(Content content) {
        String status = content.getContentStatus();
        if ("published".equals(status)) {
            throw new BusinessException(400, "内容已发布");
        }
        if (!"approved".equals(status)) {
            throw new BusinessException(400, "内容未审核通过，无法发布");
        }
    }

    @Transactional
    public PublishRecord executeScheduledPublish(String publishId, String operatorId, String operatorName) {
        PublishRecord scheduledRecord = publishRecordRepository.findById(publishId)
            .orElseThrow(() -> new BusinessException(404, "发布记录不存在"));

        if (!"scheduled".equals(scheduledRecord.getPublishStatus())) {
            throw new BusinessException(400, "发布记录不在待发布状态");
        }

        Content content = contentService.getContentById(scheduledRecord.getContentId());

        scheduledRecord.setPublishStatus("published");
        scheduledRecord.setPublishTime(LocalDateTime.now());

        PublishRecord updatedRecord = publishRecordRepository.save(scheduledRecord);

        content.setContentStatus("published");
        content.setPublishedAt(LocalDateTime.now());
        contentService.updateStatus(scheduledRecord.getContentId(), "published", operatorId);

        publishWarningService.cancelWarningsByContentId(scheduledRecord.getContentId());

        logger.info("执行定时发布: publishId={}, contentId={}, operatorId={}", 
            publishId, scheduledRecord.getContentId(), operatorId);

        return updatedRecord;
    }

    @Transactional
    public PublishRecord unpublishContent(String contentId, String operatorId, String operatorName) {
        Content content = contentService.getContentById(contentId);

        if (!"published".equals(content.getContentStatus())) {
            throw new BusinessException(400, "内容未发布，无法下架");
        }

        PublishRecord publishRecord = new PublishRecord();
        publishRecord.setPublishId("publish_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        publishRecord.setContentId(contentId);
        publishRecord.setPublishStatus("unpublished");
        publishRecord.setPublishTime(LocalDateTime.now());
        publishRecord.setPublisherId(operatorId);
        publishRecord.setPublisherName(operatorName);

        Map<String, String> config = new HashMap<>();
        config.put("action", "unpublish");
        publishRecord.setPublishConfig(config);

        PublishRecord savedRecord = publishRecordRepository.save(publishRecord);

        content.setContentStatus("unpublished");
        content.setUnpublishedAt(LocalDateTime.now());
        contentService.updateStatus(contentId, "unpublished", operatorId);

        recordUnpublishHistory(content, publishRecord, operatorId, operatorName);

        publishWarningService.cancelWarningsByContentId(contentId);

        logger.info("执行内容下架: contentId={}, operatorId={}", contentId, operatorId);

        return savedRecord;
    }

    private void recordPublishHistory(Content content, PublishRecord publishRecord, PublishExecuteRequest request) {
        HistoryRecord history = new HistoryRecord();
        history.setHistoryId("history_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        history.setContentId(request.getContentId());
        history.setOperationType("publish");
        history.setOperationName("scheduled".equals(publishRecord.getPublishStatus()) ? "定时发布设置" : "内容发布");
        history.setOperationDetail("发布渠道: " + publishRecord.getPublishChannel());
        history.setOperatorId(request.getPublisherId());
        history.setOperatorName(request.getPublisherName());
        history.setOperationTime(LocalDateTime.now());
        history.setPreviousStatus("approved");
        history.setNewStatus(publishRecord.getPublishStatus());
        historyRecordRepository.save(history);
    }

    private void recordUnpublishHistory(Content content, PublishRecord publishRecord, String operatorId, String operatorName) {
        HistoryRecord history = new HistoryRecord();
        history.setHistoryId("history_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        history.setContentId(content.getContentId());
        history.setOperationType("unpublish");
        history.setOperationName("内容下架");
        history.setOperatorId(operatorId);
        history.setOperatorName(operatorName);
        history.setOperationTime(LocalDateTime.now());
        history.setPreviousStatus("published");
        history.setNewStatus("unpublished");
        historyRecordRepository.save(history);
    }

    public List<PublishRecord> getPublishesByContentId(String contentId) {
        return publishRecordRepository.findByContentId(contentId);
    }

    public List<PublishRecord> getPublishesByStatus(String status) {
        return publishRecordRepository.findByPublishStatus(status);
    }

    public List<PublishRecord> getPublishesByChannel(String channel) {
        return publishRecordRepository.findByPublishChannel(channel);
    }

    public PublishRecord getPublishById(String publishId) {
        return publishRecordRepository.findById(publishId)
                .orElseThrow(() -> new BusinessException(404, "发布记录不存在"));
    }

    public List<PublishRecord> getAllPublishes() {
        return publishRecordRepository.findAll();
    }

    public long getScheduledPublishCount() {
        List<PublishRecord> scheduledRecords = publishRecordRepository.findByPublishStatus("scheduled");
        return scheduledRecords != null ? scheduledRecords.size() : 0;
    }
}
