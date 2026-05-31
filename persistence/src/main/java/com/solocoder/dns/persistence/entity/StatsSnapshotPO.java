package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("stats_snapshot")
public class StatsSnapshotPO {
    @TableId(type = IdType.INPUT)
    private String snapshotId;
    private LocalDateTime timestamp;
    private String metrics;
    private String dimensions;
}
