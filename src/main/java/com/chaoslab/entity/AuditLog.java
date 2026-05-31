package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName("audit_log")
public class AuditLog {

    private Long id;
    private String auditId;
    private String commandId;
    private String eventId;
    private String action;
    private String actor;
    private String resourceType;
    private String resourceId;
    private Map<String, Object> oldValue;
    private Map<String, Object> newValue;
    private String ipAddress;
    private String userAgent;
    private List<String> complianceTags;
    private LocalDateTime createdAt;
}
