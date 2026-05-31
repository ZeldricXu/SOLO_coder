package com.meshcontrol.dns.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "dns_cache", autoResultMap = true)
public class DnsCache extends BaseEntity {

    private String cacheKey;
    private String queryType;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> responses;

    private LocalDateTime expiresAt;
    private Integer hitCount;
}
