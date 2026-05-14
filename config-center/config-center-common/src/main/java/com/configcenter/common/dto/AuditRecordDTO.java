package com.configcenter.common.dto;

import com.configcenter.common.enums.AuditOperation;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AuditRecordDTO {
    
    private String auditId;
    private String configId;
    private AuditOperation operation;
    private String oldValue;
    private String newValue;
    private String operator;
    private LocalDateTime operatedAt;
    private String remark;
    private String ipAddress;
}
