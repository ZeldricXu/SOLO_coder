package com.cms.service;

import com.cms.entity.ReviewReminder;
import com.cms.entity.PublishWarning;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ScheduledReminderService {

    private static final Logger logger = LoggerFactory.getLogger(ScheduledReminderService.class);

    @Autowired
    private ReviewReminderService reviewReminderService;

    @Autowired
    private PublishWarningService publishWarningService;

    @Scheduled(fixedRate = 30000)
    public void processPendingReviewReminders() {
        List<ReviewReminder> pendingReminders = reviewReminderService.getPendingRemindersToProcess();
        
        if (pendingReminders.isEmpty()) {
            return;
        }

        logger.info("发现{}个待发送的审核提醒", pendingReminders.size());

        for (ReviewReminder reminder : pendingReminders) {
            try {
                processReviewReminder(reminder);
            } catch (Exception e) {
                logger.error("处理审核提醒失败: reminderId={}", reminder.getReminderId(), e);
            }
        }
    }

    @Scheduled(fixedRate = 60000)
    public void processPendingPublishWarnings() {
        List<PublishWarning> pendingWarnings = publishWarningService.getPendingWarningsToProcess();
        
        if (pendingWarnings.isEmpty()) {
            return;
        }

        logger.info("发现{}个待发送的发布预警", pendingWarnings.size());

        for (PublishWarning warning : pendingWarnings) {
            try {
                processPublishWarning(warning);
            } catch (Exception e) {
                logger.error("处理发布预警失败: warningId={}", warning.getWarningId(), e);
            }
        }
    }

    @Async
    public void processReviewReminder(ReviewReminder reminder) {
        try {
            sendReviewReminderNotification(reminder);
            
            reviewReminderService.resendReminder(reminder.getReminderId());

            logger.info("已发送审核提醒: reminderId={}, contentId={}, count={}", 
                reminder.getReminderId(), reminder.getContentId(), reminder.getReminderCount() + 1);
        } catch (Exception e) {
            logger.error("发送审核提醒通知失败: reminderId={}", reminder.getReminderId(), e);
        }
    }

    @Async
    public void processPublishWarning(PublishWarning warning) {
        try {
            sendPublishWarningNotification(warning);

            logger.info("已发送发布预警: warningId={}, contentId={}", 
                warning.getWarningId(), warning.getContentId());
        } catch (Exception e) {
            logger.error("发送发布预警通知失败: warningId={}", warning.getWarningId(), e);
        }
    }

    private void sendReviewReminderNotification(ReviewReminder reminder) {
        String reviewerId = reminder.getReviewerId();
        String reviewerName = reminder.getReviewerName();
        String contentTitle = reminder.getContentTitle();
        String urgencyLevel = reminder.getUrgencyLevel();
        String message = reminder.getReminderMessage();

        logger.info("发送审核提醒通知 - 审核员: {}({}), 内容: {}, 紧急程度: {}, 消息: {}", 
            reviewerName, reviewerId, contentTitle, urgencyLevel, message);

        sendNotification(reviewerId, "review_reminder", message, urgencyLevel);
    }

    private void sendPublishWarningNotification(PublishWarning warning) {
        String publisherId = warning.getPublisherId();
        String publisherName = warning.getPublisherName();
        String contentTitle = warning.getContentTitle();
        String importanceLevel = warning.getImportanceLevel();
        String message = warning.getWarningMessage();

        logger.info("发送发布预警通知 - 发布员: {}({}), 内容: {}, 重要程度: {}, 消息: {}", 
            publisherName, publisherId, contentTitle, importanceLevel, message);

        sendNotification(publisherId, "publish_warning", message, importanceLevel);
    }

    private void sendNotification(String recipientId, String notificationType, 
                                  String message, String priority) {
        logger.info("通知已发送 - 接收者: {}, 类型: {}, 优先级: {}, 消息: {}", 
            recipientId, notificationType, priority, message);
    }
}
