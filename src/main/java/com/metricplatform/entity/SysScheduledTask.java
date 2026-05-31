package com.metricplatform.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("sys_scheduled_task")
public class SysScheduledTask extends BaseEntity {

    @TableId(type = IdType.INPUT)
    private String taskId;

    private String taskName;

    private String taskType;

    private String cronExpression;

    private List<String> dependencies;

    private Map<String, Object> parameters;

    private String status;

    private LocalDateTime lastRunAt;

    private LocalDateTime nextRunAt;

    private Integer retryCount;

    private Long timeout;
}
