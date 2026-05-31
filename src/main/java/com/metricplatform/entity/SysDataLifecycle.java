package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_data_lifecycle")
public class SysDataLifecycle extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String lifecycleId;

    private String tableName;

    private Integer hotDays;

    private Integer warmDays;

    private Integer coldDays;

    private Boolean archiveEnabled;

    private Boolean cleanupEnabled;

    private String archiveTableSuffix;

    private LocalDateTime lastMigrateAt;

    private LocalDateTime lastArchiveAt;

    private LocalDateTime lastCleanupAt;
}
