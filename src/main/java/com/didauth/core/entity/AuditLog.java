package com.didauth.core.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_audit_log")
public class AuditLog extends BaseEntity {

    private String traceId;
    private String userId;
    private String module;
    private String operation;
    private String requestParams;
    private String responseResult;
    private String status;
    private String errorMessage;
    private String ipAddress;
    private String userAgent;
    private Long durationMs;
}
