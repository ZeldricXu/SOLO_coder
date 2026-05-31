package com.meshcontrol.dns.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "dns_zone", autoResultMap = true)
public class DnsZone extends BaseEntity {

    private String zoneId;
    private String domain;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private List<String> upstreamIds;

    private String resolutionPolicy;
    private Integer cacheTtl;
    private Boolean enabled;
}
