package com.datamasker.infrastructure.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dm_audit_log")
public class AuditLogEntity {

    @TableId(type = IdType.ASSIGN_ID)
    private String id;

    private String logHash;

    private String prevHash;

    private String operation;

    private String operator;

    private String module;

    private String detail;

    private LocalDateTime timestamp;
}
