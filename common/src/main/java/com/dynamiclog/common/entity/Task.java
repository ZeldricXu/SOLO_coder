package com.dynamiclog.common.entity;

import com.dynamiclog.common.enums.TaskStatus;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@EqualsAndHashCode(callSuper = true)
public class Task extends BaseEntity {
    private String name;
    private String description;
    private String type;
    private TaskStatus status;
    private String cronExpression;
    private Long fixedDelayMs;
    private Long fixedRateMs;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer priority = 0;
    private Integer maxRetries = 3;
    private Integer retryCount = 0;
    private Long timeoutMs;
    private String handlerClass;
    private String payload;
    private List<String> dependencies = new ArrayList<>();
    private String parentTaskId;
    private String runId;
    private Double progress;
    private String errorDetail;
}
