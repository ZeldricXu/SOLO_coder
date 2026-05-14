package com.cms.service;

import com.cms.entity.Content;
import com.cms.entity.ContentTypeConfig;
import com.cms.entity.PublishWarning;
import com.cms.exception.BusinessException;
import com.cms.repository.PublishWarningRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class PublishWarningService {

    private static final Logger logger = LoggerFactory.getLogger(PublishWarningService.class);

    private static final String IMPORTANCE_CRITICAL = "critical";
    private static final String IMPORTANCE_HIGH = "high";
    private static final String IMPORTANCE_NORMAL = "normal";
    private static final String IMPORTANCE_LOW = "low";

    private static final int WARNING_OFFSET_CRITICAL_MINUTES = 1440;
    private static final int WARNING_OFFSET_HIGH_MINUTES = 720;
    private static final int WARNING_OFFSET_NORMAL_MINUTES = 120;
    private static final int WARNING_OFFSET_LOW_MINUTES = 30;

    @Autowired
    private PublishWarningRepository publishWarningRepository;

    @Autowired
    private ContentService contentService;

    @Autowired
    private ContentTypeConfigService contentTypeConfigService;

    @Transactional
    public PublishWarning createWarning(String contentId, String publishId, LocalDateTime scheduledPublishTime,
                                        String publisherId, String publisherName, String publishChannel) {
        Content content = contentService.getContentById(contentId);

        String importanceLevel = determineImportanceLevel(content);
        int warningOffsetMinutes = calculateWarningOffset(importanceLevel);

        LocalDateTime warningTime = scheduledPublishTime.minusMinutes(warningOffsetMinutes);

        if (warningTime.isBefore(LocalDateTime.now())) {
            warningTime = LocalDateTime.now();
        }

        PublishWarning warning = new PublishWarning();
        warning.setWarningId("warning_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        warning.setPublishId(publishId);
        warning.setContentId(contentId);
        warning.setContentTitle(content.getContentTitle());
        warning.setPublisherId(publisherId);
        warning.setPublisherName(publisherName);
        warning.setWarningType("publish_pending");
        warning.setImportanceLevel(importanceLevel);
        warning.setWarningMessage(buildWarningMessage(content, importanceLevel, scheduledPublishTime));
        warning.setWarningStatus("pending");
        warning.setWarningTime(warningTime);
        warning.setScheduledPublishTime(scheduledPublishTime);
        warning.setWarningOffsetMinutes(warningOffsetMinutes);
        warning.setPublishChannel(publishChannel);

        PublishWarning saved = publishWarningRepository.save(warning);

        logger.info("创建发布预警: contentId={}, importance={}, offset={}分钟, warningTime={}",
            contentId, importanceLevel, warningOffsetMinutes, warningTime);

        return saved;
    }

    @Async
    @Transactional
    public void createWarningAsync(String contentId, String publishId, LocalDateTime scheduledPublishTime,
                                   String publisherId, String publisherName, String publishChannel) {
        try {
            createWarning(contentId, publishId, scheduledPublishTime, publisherId, publisherName, publishChannel);
        } catch (Exception e) {
            logger.error("异步创建发布预警失败: contentId={}", contentId, e);
        }
    }

    @Transactional
    public void acknowledgeWarning(String warningId, String acknowledgedById, String acknowledgedByName) {
        PublishWarning warning = publishWarningRepository.findById(warningId)
            .orElseThrow(() -> new BusinessException(404, "预警不存在"));

        warning.setWarningStatus("acknowledged");
        warning.setAcknowledgedTime(LocalDateTime.now());
        warning.setAcknowledgedById(acknowledgedById);
        warning.setAcknowledgedByName(acknowledgedByName);

        publishWarningRepository.save(warning);

        logger.info("确认发布预警: warningId={}, acknowledgedBy={}", warningId, acknowledgedById);
    }

    @Transactional
    public void acknowledgeAllWarningsByPublisher(String publisherId, String acknowledgedById, String acknowledgedByName) {
        List<PublishWarning> warnings = publishWarningRepository
            .findByPublisherIdAndWarningStatus(publisherId, "pending");

        for (PublishWarning warning : warnings) {
            warning.setWarningStatus("acknowledged");
            warning.setAcknowledgedTime(LocalDateTime.now());
            warning.setAcknowledgedById(acknowledgedById);
            warning.setAcknowledgedByName(acknowledgedByName);
        }

        publishWarningRepository.saveAll(warnings);

        logger.info("批量确认发布预警: publisherId={}, count={}", publisherId, warnings.size());
    }

    @Transactional
    public void cancelWarningsByContentId(String contentId) {
        List<PublishWarning> warnings = publishWarningRepository
            .findByContentId(contentId);

        for (PublishWarning warning : warnings) {
            if ("pending".equals(warning.getWarningStatus())) {
                warning.setWarningStatus("cancelled");
            }
        }

        publishWarningRepository.saveAll(warnings);

        logger.info("取消内容发布预警: contentId={}, count={}", contentId, warnings.size());
    }

    public int calculateWarningOffset(String importanceLevel) {
        if (importanceLevel == null) {
            return WARNING_OFFSET_NORMAL_MINUTES;
        }

        switch (importanceLevel.toLowerCase()) {
            case IMPORTANCE_CRITICAL:
                return WARNING_OFFSET_CRITICAL_MINUTES;
            case IMPORTANCE_HIGH:
                return WARNING_OFFSET_HIGH_MINUTES;
            case IMPORTANCE_LOW:
                return WARNING_OFFSET_LOW_MINUTES;
            default:
                return WARNING_OFFSET_NORMAL_MINUTES;
        }
    }

    public String determineImportanceLevel(Content content) {
        if (content.getContentType() != null) {
            try {
                ContentTypeConfig config = contentTypeConfigService
                    .getActiveConfigByCode(content.getContentType());
                if (config != null && config.getDefaultImportanceLevel() != null) {
                    return config.getDefaultImportanceLevel();
                }
            } catch (Exception e) {
                logger.warn("获取内容类型配置失败，使用默认重要程度: contentType={}", content.getContentType());
            }
        }

        return IMPORTANCE_NORMAL;
    }

    public List<PublishWarning> getPendingWarningsToProcess() {
        return publishWarningRepository.findPendingWarningsToSend(LocalDateTime.now());
    }

    public List<PublishWarning> getWarningsByPublisherId(String publisherId) {
        return publishWarningRepository.findByPublisherId(publisherId);
    }

    public List<PublishWarning> getPendingWarningsByPublisherId(String publisherId) {
        return publishWarningRepository.findByPublisherIdAndWarningStatus(publisherId, "pending");
    }

    public long countPendingWarningsByPublisherId(String publisherId) {
        return publishWarningRepository.countPendingWarningsByPublisherId(publisherId);
    }

    public List<PublishWarning> getWarningsByContentId(String contentId) {
        return publishWarningRepository.findByContentId(contentId);
    }

    public PublishWarning getWarningById(String warningId) {
        return publishWarningRepository.findById(warningId)
            .orElseThrow(() -> new BusinessException(404, "预警不存在"));
    }

    public List<PublishWarning> getWarningsByImportanceLevel(String importanceLevel) {
        return publishWarningRepository.findByImportanceLevel(importanceLevel);
    }

    private String buildWarningMessage(Content content, String importanceLevel, LocalDateTime scheduledPublishTime) {
        String importanceText = getImportanceText(importanceLevel);
        return String.format("[%s] 内容「%s」将于 %s 发布，请及时确认。",
            importanceText, content.getContentTitle(), scheduledPublishTime.toString());
    }

    private String getImportanceText(String importanceLevel) {
        if (importanceLevel == null) {
            return "普通";
        }

        switch (importanceLevel.toLowerCase()) {
            case IMPORTANCE_CRITICAL:
                return "重要";
            case IMPORTANCE_HIGH:
                return "高";
            case IMPORTANCE_LOW:
                return "低";
            default:
                return "普通";
        }
    }
}
