package com.solo.config.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@TableName("snapshots")
public class Snapshot {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("snapshot_id")
    private String snapshotId;

    @TableField("aggregate_id")
    private String aggregateId;

    @TableField("aggregate_type")
    private String aggregateType;

    private Integer version;

    @TableField(typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> state;

    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
