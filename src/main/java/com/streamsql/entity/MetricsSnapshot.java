package com.streamsql.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.streamsql.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("metrics_snapshot")
public class MetricsSnapshot extends BaseEntity {

    @TableId(type = IdType.ASSIGN_UUID)
    private String snapshotId;

    private LocalDateTime timestamp;

    private String metrics;

    private String dimensions;
}
