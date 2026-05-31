package com.iotplatform.notification.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.iotplatform.notification.dto.NotificationSendDTO;
import com.iotplatform.notification.dto.TemplateCreateDTO;
import com.iotplatform.notification.entity.Notification;
import com.iotplatform.notification.entity.NotificationTemplate;
import reactor.core.publisher.Mono;
import java.util.List;
import java.util.Map;

public interface NotificationService {

    Mono<Notification> sendNotification(NotificationSendDTO dto);

    Mono<List<Notification>> sendBatchNotifications(List<NotificationSendDTO> dtos);

    Mono<Notification> getNotification(String notificationId);

    Mono<IPage<Notification>> listNotifications(String channelType, String status, String recipient,
                                                 Integer pageNum, Integer pageSize);

    Mono<NotificationTemplate> createTemplate(TemplateCreateDTO dto);

    Mono<NotificationTemplate> getTemplate(String templateCode, String channelType);

    Mono<Void> retryFailedNotification(String notificationId);

    Mono<Map<String, Long>> getNotificationStats();

    Mono<List<Notification>> getPendingNotifications(int limit);

    Mono<Void> processPendingNotifications();
}
