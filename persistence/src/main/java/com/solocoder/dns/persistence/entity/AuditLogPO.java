package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("audit_log")
public class AuditLogPO {
    @TableId(type = IdType.INPUT)
    private String logId;
    private String commandId;
    private String userId;
    private String action;
    private String resourceType;
    private String resourceId;
    private String beforeState;
    private String afterState;
    private LocalDateTime createdAt;
    private String clientIp;
    private String userAgent;
}
