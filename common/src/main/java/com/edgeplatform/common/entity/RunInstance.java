package com.edgeplatform.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("run_instance")
public class RunInstance extends BaseEntity {

    private String runId;

    private String entityId;

    private String phase;

    private Double progress;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private String errorDetail;
}
