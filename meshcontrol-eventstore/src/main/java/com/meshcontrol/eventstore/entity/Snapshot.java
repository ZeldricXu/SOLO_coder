package com.meshcontrol.eventstore.entity;

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
@TableName(value = "snapshot", autoResultMap = true)
public class Snapshot extends BaseEntity {

    private String snapshotId;
    private String aggregateId;
    private String aggregateType;
    private Integer version;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> state;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, String> dimensions;

    private LocalDateTime timestamp;
}
