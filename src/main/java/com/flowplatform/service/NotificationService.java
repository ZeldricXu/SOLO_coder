package com.flowplatform.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.flowplatform.entity.Notification;
import java.util.List;

public interface NotificationService extends IService<Notification> {
    int countUnread(Long userId);
    List<Notification> listByUserId(Long userId);
    boolean markRead(Long notificationId);
    boolean markAllRead(Long userId);
    void sendNotification(Long userId, String title, String content, String type, String bizType, Long bizId);
}
