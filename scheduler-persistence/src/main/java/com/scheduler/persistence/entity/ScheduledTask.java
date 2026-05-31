package com.scheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.scheduler.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("scheduled_tasks")
public class ScheduledTask extends BaseEntity {
    private String taskId;
    private String name;
    private String description;
    private String taskType;
    private String cronExpression;
    private Long fixedDelay;
    private Long fixedRate;
    private String targetService;
    private String targetMethod;
    private Map<String, Object> parameters;
    private String status;
    private Integer priority;
    private Integer maxRetries;
    private Integer retryInterval;
    private Instant lastExecutionTime;
    private Instant nextExecutionTime;
    private String createdBy;
    private String namespace;
    private Map<String, String> labels;
    private String configId;
}
