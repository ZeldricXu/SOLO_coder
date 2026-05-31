package com.taskflow.notification.model;

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class NotificationTemplate {
    private String templateId;
    private String name;
    private String type;
    private String channel;
    private String subject;
    private String content;
    private List<String> variables;
    private Map<String, Object> defaultValues;
    private boolean enabled;
}
