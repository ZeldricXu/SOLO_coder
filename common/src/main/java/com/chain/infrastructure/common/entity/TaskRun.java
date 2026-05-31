package com.chain.infrastructure.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_run")
public class TaskRun extends BaseEntity {

    private String runId;

    private String entityId;

    private String phase;

    private Double progress;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private String errorDetail;

    private String status;
}
