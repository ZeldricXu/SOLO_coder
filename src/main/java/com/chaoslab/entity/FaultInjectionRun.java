package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fault_injection_run")
public class FaultInjectionRun extends BaseEntity {

    private String runId;
    private String scenarioId;
    private String status;
    private String phase;
    private List<String> targets;
    private LocalDateTime startedAt;
    private LocalDateTime endedAt;
    private Boolean rollbackTriggered;
    private String rollbackReason;
    private LocalDateTime rollbackCompletedAt;
    private Map<String, Object> metrics;
    private String errorDetail;
}
