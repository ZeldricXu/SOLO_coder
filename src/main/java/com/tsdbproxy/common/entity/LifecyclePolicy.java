package com.tsdbproxy.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_lifecycle_policy")
public class LifecyclePolicy extends BaseEntity {

    private String name;

    private String tableName;

    private String timeColumn;

    private Integer hotDays;

    private Integer coldDays;

    private Integer archiveDays;

    private String archiveLocation;

    private Integer enabled;

    private LocalDateTime lastExecutionTime;
}
