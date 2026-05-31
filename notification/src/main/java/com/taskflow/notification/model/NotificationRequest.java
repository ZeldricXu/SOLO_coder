package com.taskflow.notification.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class NotificationRequest {
    private String tenantId;
    private String templateId;
    private String type;
    private String channel;
    private List<String> receivers;
    private String subject;
    private String content;
    private Map<String, Object> variables;
    private String sender;
    private Map<String, Object> config;
}
