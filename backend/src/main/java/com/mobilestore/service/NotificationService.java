package com.mobilestore.service;

import com.mobilestore.entity.Notification;
import com.mobilestore.repository.NotificationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class NotificationService {

    @Autowired
    private NotificationRepository notificationRepository;

    @Transactional
    public Notification sendNotification(String recipientId, String type, String title, String content,
                                          String relatedType, String relatedId) {
        Notification notification = new Notification();
        notification.setNotificationId("notif_" + UUID.randomUUID().toString().substring(0, 8));
        notification.setRecipientId(recipientId);
        notification.setType(type);
        notification.setTitle(title);
        notification.setContent(content);
        notification.setRelatedType(relatedType);
        notification.setRelatedId(relatedId);
        notification.setIsRead(false);

        return notificationRepository.save(notification);
    }

    public List<Notification> getNotifications(String recipientId, Boolean isRead) {
        if (isRead != null) {
            return notificationRepository.findByRecipientIdAndIsReadOrderByCreatedAtDesc(recipientId, isRead);
        }
        return notificationRepository.findByRecipientIdOrderByCreatedAtDesc(recipientId);
    }

    public long getUnreadCount(String recipientId) {
        return notificationRepository.countByRecipientIdAndIsRead(recipientId, false);
    }

    @Transactional
    public Notification markAsRead(String notificationId) {
        Notification notification = notificationRepository.findById(notificationId)
                .orElseThrow(() -> new IllegalArgumentException("通知不存在"));
        notification.setIsRead(true);
        return notificationRepository.save(notification);
    }

    @Transactional
    public int markAllAsRead(String recipientId) {
        List<Notification> notifications = notificationRepository
                .findByRecipientIdAndIsReadOrderByCreatedAtDesc(recipientId, false);
        int count = 0;
        for (Notification notification : notifications) {
            notification.setIsRead(true);
            notificationRepository.save(notification);
            count++;
        }
        return count;
    }
}
