package com.datamasker.interfaces.dto.audit;

import lombok.Data;

@Data
public class AuditLogResponse {

    private String logId;

    private String logHash;

    private String operation;

    private String operator;

    private String module;

    private String detail;

    private String timestamp;
}
