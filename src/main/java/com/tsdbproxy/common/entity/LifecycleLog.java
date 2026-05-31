package com.tsdbproxy.common.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_lifecycle_log")
public class LifecycleLog extends BaseEntity {

    private Long policyId;

    private String operationType;

    private String sourceTable;

    private String targetTable;

    private Long processedRows;

    private String status;

    private String errorMessage;

    private LocalDateTime startTime;

    private LocalDateTime endTime;
}
