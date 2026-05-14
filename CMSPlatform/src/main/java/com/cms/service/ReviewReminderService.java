package com.cms.service;

import com.cms.entity.Content;
import com.cms.entity.ContentTypeConfig;
import com.cms.entity.ReviewReminder;
import com.cms.exception.BusinessException;
import com.cms.repository.ReviewReminderRepository;
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
public class ReviewReminderService {

    private static final Logger logger = LoggerFactory.getLogger(ReviewReminderService.class);

    private static final int MAX_REMINDER_COUNT = 10;

    private static final String URGENCY_CRITICAL = "critical";
    private static final String URGENCY_URGENT = "urgent";
    private static final String URGENCY_HIGH = "high";
    private static final String URGENCY_NORMAL = "normal";

    private static final int FREQUENCY_CRITICAL_MINUTES = 5;
    private static final int FREQUENCY_URGENT_MINUTES = 15;
    private static final int FREQUENCY_HIGH_MINUTES = 30;
    private static final int FREQUENCY_NORMAL_MINUTES = 60;

    @Autowired
    private ReviewReminderRepository reviewReminderRepository;

    @Autowired
    private ContentService contentService;

    @Autowired
    private ContentTypeConfigService contentTypeConfigService;

    @Transactional
    public ReviewReminder createReminder(String contentId, String reviewerId, String reviewerName) {
        Content content = contentService.getContentById(contentId);
        
        if (!"pending_review".equals(content.getContentStatus())) {
            throw new BusinessException(400, "内容不在待审核状态，无法创建提醒");
        }

        String urgencyLevel = determineUrgencyLevel(content);
        int frequencyMinutes = calculateReminderFrequency(urgencyLevel);
        
        ReviewReminder reminder = new ReviewReminder();
        reminder.setReminderId("reminder_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10));
        reminder.setContentId(contentId);
        reminder.setContentTitle(content.getContentTitle());
        reminder.setReviewerId(reviewerId);
        reminder.setReviewerName(reviewerName);
        reminder.setReminderType("review_pending");
        reminder.setUrgencyLevel(urgencyLevel);
        reminder.setReminderMessage(buildReminderMessage(content, urgencyLevel));
        reminder.setReminderStatus("unread");
        reminder.setReminderTime(LocalDateTime.now());
        reminder.setReminderFrequencyMinutes(frequencyMinutes);
        reminder.setReminderCount(1);
        reminder.setNextReminderTime(LocalDateTime.now().plusMinutes(frequencyMinutes));

        ReviewReminder saved = reviewReminderRepository.save(reminder);
        
        logger.info("创建审核提醒: contentId={}, urgencyLevel={}, frequency={}分钟", 
            contentId, urgencyLevel, frequencyMinutes);
        
        return saved;
    }

    @Async
    @Transactional
    public void createReminderAsync(String contentId, String reviewerId, String reviewerName) {
        try {
            createReminder(contentId, reviewerId, reviewerName);
        } catch (Exception e) {
            logger.error("异步创建审核提醒失败: contentId={}", contentId, e);
        }
    }

    @Transactional
    public ReviewReminder resendReminder(String reminderId) {
        ReviewReminder reminder = reviewReminderRepository.findById(reminderId)
            .orElseThrow(() -> new BusinessException(404, "提醒不存在"));

        if (reminder.getReminderCount() >= MAX_REMINDER_COUNT) {
            throw new BusinessException(400, "已达到最大提醒次数");
        }

        int frequencyMinutes = reminder.getReminderFrequencyMinutes();
        
        reminder.setReminderCount(reminder.getReminderCount() + 1);
        reminder.setReminderTime(LocalDateTime.now());
        reminder.setReminderStatus("unread");
        reminder.setNextReminderTime(LocalDateTime.now().plusMinutes(frequencyMinutes));

        ReviewReminder saved = reviewReminderRepository.save(reminder);
        
        logger.info("重发审核提醒: reminderId={}, count={}", reminderId, saved.getReminderCount());
        
        return saved;
    }

    @Transactional
    public void markAsRead(String reminderId) {
        ReviewReminder reminder = reviewReminderRepository.findById(reminderId)
            .orElseThrow(() -> new BusinessException(404, "提醒不存在"));

        reminder.setReminderStatus("read");
        reminder.setReadTime(LocalDateTime.now());
        reminder.setNextReminderTime(null);

        reviewReminderRepository.save(reminder);
    }

    @Transactional
    public void markAllAsReadByReviewer(String reviewerId) {
        List<ReviewReminder> reminders = reviewReminderRepository
            .findByReviewerIdAndReminderStatus(reviewerId, "unread");
        
        for (ReviewReminder reminder : reminders) {
            reminder.setReminderStatus("read");
            reminder.setReadTime(LocalDateTime.now());
            reminder.setNextReminderTime(null);
        }
        
        reviewReminderRepository.saveAll(reminders);
    }

    @Transactional
    public void cancelRemindersByContentId(String contentId) {
        List<ReviewReminder> reminders = reviewReminderRepository
            .findByContentIdAndReminderStatus(contentId, "unread");
        
        for (ReviewReminder reminder : reminders) {
            reminder.setReminderStatus("cancelled");
            reminder.setNextReminderTime(null);
        }
        
        reviewReminderRepository.saveAll(reminders);
        
        logger.info("取消内容审核提醒: contentId={}, count={}", contentId, reminders.size());
    }

    public int calculateReminderFrequency(String urgencyLevel) {
        if (urgencyLevel == null) {
            return FREQUENCY_NORMAL_MINUTES;
        }
        
        switch (urgencyLevel.toLowerCase()) {
            case URGENCY_CRITICAL:
                return FREQUENCY_CRITICAL_MINUTES;
            case URGENCY_URGENT:
                return FREQUENCY_URGENT_MINUTES;
            case URGENCY_HIGH:
                return FREQUENCY_HIGH_MINUTES;
            default:
                return FREQUENCY_NORMAL_MINUTES;
        }
    }

    public String determineUrgencyLevel(Content content) {
        if (content.getContentType() != null) {
            try {
                ContentTypeConfig config = contentTypeConfigService
                    .getActiveConfigByCode(content.getContentType());
                if (config != null && config.getDefaultUrgencyLevel() != null) {
                    return config.getDefaultUrgencyLevel();
                }
            } catch (Exception e) {
                logger.warn("获取内容类型配置失败，使用默认紧急程度: contentType={}", content.getContentType());
            }
        }
        
        return URGENCY_NORMAL;
    }

    public List<ReviewReminder> getPendingRemindersToProcess() {
        return reviewReminderRepository.findPendingRemindersToSend("unread", LocalDateTime.now());
    }

    public List<ReviewReminder> getRemindersByReviewerId(String reviewerId) {
        return reviewReminderRepository.findByReviewerId(reviewerId);
    }

    public List<ReviewReminder> getUnreadRemindersByReviewerId(String reviewerId) {
        return reviewReminderRepository.findByReviewerIdAndReminderStatus(reviewerId, "unread");
    }

    public long countUnreadRemindersByReviewerId(String reviewerId) {
        return reviewReminderRepository.countUnreadRemindersByReviewerId(reviewerId);
    }

    public List<ReviewReminder> getRemindersByContentId(String contentId) {
        return reviewReminderRepository.findByContentId(contentId);
    }

    public ReviewReminder getReminderById(String reminderId) {
        return reviewReminderRepository.findById(reminderId)
            .orElseThrow(() -> new BusinessException(404, "提醒不存在"));
    }

    private String buildReminderMessage(Content content, String urgencyLevel) {
        String urgencyText = getUrgencyText(urgencyLevel);
        return String.format("[%s] 内容「%s」等待您的审核，请及时处理。", 
            urgencyText, content.getContentTitle());
    }

    private String getUrgencyText(String urgencyLevel) {
        if (urgencyLevel == null) {
            return "普通";
        }
        
        switch (urgencyLevel.toLowerCase()) {
            case URGENCY_CRITICAL:
                return "紧急";
            case URGENCY_URGENT:
                return "加急";
            case URGENCY_HIGH:
                return "高";
            default:
                return "普通";
        }
    }
}
