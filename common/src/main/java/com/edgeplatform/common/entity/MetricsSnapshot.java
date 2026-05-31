package com.edgeplatform.common.entity;

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName(value = "metrics_snapshot", autoResultMap = true)
public class MetricsSnapshot extends BaseEntity {

    private String snapshotId;

    private LocalDateTime timestamp;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, Object> metrics;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private Map<String, String> dimensions;
}
