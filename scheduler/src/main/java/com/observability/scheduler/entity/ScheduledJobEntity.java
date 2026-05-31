package com.observability.scheduler.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.observability.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_scheduled_job")
public class ScheduledJobEntity extends BaseEntity {

    private String jobId;

    private String name;

    private String cronExpression;

    private String jobType;

    private Map<String, Object> jobParams;

    private String status;

    private LocalDateTime lastRunAt;

    private LocalDateTime nextRunAt;
}
