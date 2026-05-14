package com.logistics.service;

import com.logistics.constant.LogisticsConstants;
import com.logistics.entity.Notification;
import com.logistics.repository.NotificationRepository;
import com.logistics.util.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class StatusService {

    private final NotificationRepository notificationRepository;

    @Transactional
    public Notification sendNotification(String logisticsId, String notifyType, String notifyStatus) {
        Notification notification = new Notification();
        notification.setNotifyId(IdGenerator.generateNotifyId());
        notification.setLogisticsId(logisticsId);
        notification.setNotifyType(notifyType);
        notification.setNotifyStatus(notifyStatus);
        notification.setNotifyTime(LocalDateTime.now());

        Notification saved = notificationRepository.save(notification);

        log.info("发送通知 - logisticsId: {}, type: {}, status: {}", logisticsId, notifyType, notifyStatus);

        return saved;
    }

    public List<Notification> getNotificationsByLogisticsId(String logisticsId) {
        return notificationRepository.findByLogisticsId(logisticsId);
    }

    public List<Notification> getNotificationsByUserId(String userId) {
        return notificationRepository.findByUserId(userId);
    }

    public List<Notification> getUnreadNotificationsByUserId(String userId) {
        return notificationRepository.findByUserIdAndIsRead(userId, false);
    }

    @Transactional
    public void markAsRead(String notifyId) {
        notificationRepository.findById(notifyId).ifPresent(notification -> {
            notification.setIsRead(true);
            notificationRepository.save(notification);
        });
    }
}
