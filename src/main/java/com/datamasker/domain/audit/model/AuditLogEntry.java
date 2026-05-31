package com.datamasker.domain.audit.model;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AuditLogEntry {

    private String logId;

    private String logHash;

    private String prevHash;

    private String operation;

    private String operator;

    private String module;

    private String detail;

    private LocalDateTime timestamp;
}
