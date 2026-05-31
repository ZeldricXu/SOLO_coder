package com.chain.infrastructure.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("metrics_snapshot")
public class MetricsSnapshot extends BaseEntity {

    private String snapshotId;

    private LocalDateTime timestamp;

    private String metrics;

    private String dimensions;
}
