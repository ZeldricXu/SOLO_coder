package com.meshcontrol.audit.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class CommandQueryRequest {

    private String commandType;
    private String aggregateId;
    private String aggregateType;
    private String executedBy;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer pageNum = 1;
    private Integer pageSize = 20;
}
