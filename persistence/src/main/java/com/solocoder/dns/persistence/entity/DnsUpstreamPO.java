package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("dns_upstream")
public class DnsUpstreamPO {
    @TableId(type = IdType.INPUT)
    private String id;
    private String name;
    private String host;
    private Integer port;
    private Integer priority;
    private Integer weight;
    private String protocol;
    private Boolean enabled;
    private Integer timeoutMs;
    private Integer maxRetries;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
