package com.orchestration.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.orchestration.common.base.TenantEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("task_instance")
public class TaskInstance extends TenantEntity {

    private Long taskId;

    private String instanceNo;

    private Long parentInstanceId;

    private String status;

    private String phase;

    private BigDecimal progress;

    private String inputData;

    private String outputData;

    private String errorDetail;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime scheduledAt;

    private Integer retryCount;

    private String workerNode;
}
