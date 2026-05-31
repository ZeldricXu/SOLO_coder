package com.orchestration.scheduler.dto;

import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
public class TaskInstanceVO {

    private Long id;

    private Long taskId;

    private String instanceNo;

    private String status;

    private String phase;

    private BigDecimal progress;

    private String outputData;

    private String errorDetail;

    private LocalDateTime startedAt;

    private LocalDateTime completedAt;

    private LocalDateTime createdAt;
}
