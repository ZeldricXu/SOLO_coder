package com.solo.config.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("dns_records")
public class DnsRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String domain;

    @TableField("record_type")
    private String recordType;

    private String value;

    private Integer ttl;

    private String upstream;

    @TableField("cached_at")
    private LocalDateTime cachedAt;

    @TableField("expires_at")
    private LocalDateTime expiresAt;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
