package com.chaoslab.modules.eventstore.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;

@Data
public class TimeTravelQueryRequest {

    private String aggregateId;

    private String aggregateType;

    private LocalDateTime timestamp;

    private Long sequenceNumber;
}
