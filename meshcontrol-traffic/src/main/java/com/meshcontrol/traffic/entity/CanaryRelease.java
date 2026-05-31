package com.meshcontrol.traffic.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.meshcontrol.common.base.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "canary_release", autoResultMap = true)
public class CanaryRelease extends BaseEntity {

    private String releaseId;
    private String name;
    private String serviceName;
    private String namespace;
    private String primaryVersion;
    private String canaryVersion;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> trafficSplit;

    private String strategy;
    private String status;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private LocalDateTime rollbackAt;
}
