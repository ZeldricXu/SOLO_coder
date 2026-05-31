package com.taskflow.notification.service;

import com.taskflow.common.utils.JsonUtils;
import com.taskflow.notification.model.NotificationRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationEventPublisher {

    private final NotificationService notificationService;

    public void publishTaskCompleted(String tenantId, String taskName, List<String> recipients) {
        NotificationRequest request = new NotificationRequest();
        request.setTenantId(tenantId);
        request.setType("task");
        request.setChannel("email");
        request.setReceivers(recipients);
        request.setSubject("任务执行完成通知");
        request.setContent("任务 [" + taskName + "] 已成功执行完成。");
        notificationService.sendAsync(request);
    }

    public void publishTaskFailed(String tenantId, String taskName, String error, List<String> recipients) {
        NotificationRequest request = new NotificationRequest();
        request.setTenantId(tenantId);
        request.setType("task");
        request.setChannel("email");
        request.setReceivers(recipients);
        request.setSubject("任务执行失败通知");
        request.setContent("任务 [" + taskName + "] 执行失败，错误信息：" + error);
        notificationService.sendAsync(request);
    }

    public void publishSystemAlert(String tenantId, String alertType, String message, List<String> recipients) {
        NotificationRequest request = new NotificationRequest();
        request.setTenantId(tenantId);
        request.setType("system");
        request.setChannel("dingtalk");
        request.setReceivers(recipients);
        request.setSubject("系统告警 - " + alertType);
        request.setContent("【系统告警】" + message);
        notificationService.sendAsync(request);
    }

    public void publishBillReady(String tenantId, String billPeriod, String amount, List<String> recipients) {
        NotificationRequest request = new NotificationRequest();
        request.setTenantId(tenantId);
        request.setTemplateId("bill_ready");
        request.setChannel("email");
        request.setReceivers(recipients);
        request.setVariables(Map.of(
                "tenantId", tenantId,
                "billPeriod", billPeriod,
                "amount", amount
        ));
        notificationService.sendAsync(request);
    }
}
