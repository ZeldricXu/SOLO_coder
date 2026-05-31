package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("stats_snapshot")
public class StatsSnapshot extends BaseEntity {

    private String snapshotId;
    private LocalDateTime timestamp;
    private Map<String, Object> metrics;
    private Map<String, Object> dimensions;
}
