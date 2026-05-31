package com.solocoder.platform.notification.service;

import com.solocoder.platform.notification.model.NotificationRequest;
import com.solocoder.platform.notification.model.NotificationResult;
import com.solocoder.platform.notification.model.NotificationTemplate;

import java.util.Collection;
import java.util.List;

public interface NotificationService {

    NotificationResult send(NotificationRequest request);

    List<NotificationResult> sendBatch(List<NotificationRequest> requests);

    void registerTemplate(NotificationTemplate template);

    NotificationTemplate getTemplate(String templateId);

    Collection<NotificationTemplate> getAllTemplates();
}
