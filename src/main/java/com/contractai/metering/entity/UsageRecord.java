package com.contractai.metering.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.contractai.common.entity.TenantBaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("usage_record")
public class UsageRecord extends TenantBaseEntity {

    @TableField("resource_type")
    private String resourceType;

    @TableField("usage_amount")
    private Long usageAmount;

    @TableField("unit")
    private String unit;

    @TableField("usage_time")
    private LocalDateTime usageTime;

    @TableField("source")
    private String source;

    @TableField("source_id")
    private String sourceId;

    @TableField(value = "attributes", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> attributes;
}
