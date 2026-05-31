package com.meshcontrol.eventstore.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EventQueryRequest {

    private String aggregateId;
    private String aggregateType;
    private String eventType;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Integer pageNum = 1;
    private Integer pageSize = 20;
}
