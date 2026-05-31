package com.edgescheduler.domain.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("snapshot")
public class SnapshotEntity extends BaseEntity {

    @TableField("snapshot_id")
    private String snapshotId;

    @TableField("timestamp")
    private LocalDateTime timestamp;

    @TableField(value = "metrics", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField(value = "dimensions", typeHandler = com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler.class)
    private Map<String, Object> dimensions;
}
