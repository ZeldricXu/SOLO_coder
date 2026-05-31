package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("dns_cache")
public class DnsCachePO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String domain;
    private Integer recordType;
    private String recordData;
    private Long ttl;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private Integer hitCount;
}
