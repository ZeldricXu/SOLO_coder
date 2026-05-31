package com.solocoder.dns.audit.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class AuditLog implements Serializable {
    private String logId;
    private String commandId;
    private String userId;
    private String action;
    private String resourceType;
    private String resourceId;
    private Map<String, Object> beforeState;
    private Map<String, Object> afterState;
    private LocalDateTime createdAt;
    private String clientIp;
    private String userAgent;
}
