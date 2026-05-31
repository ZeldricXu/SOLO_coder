package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName("event_log")
public class EventLog {

    private Long id;
    private String eventId;
    private String aggregateId;
    private String aggregateType;
    private String eventType;
    private Integer eventVersion;
    private Map<String, Object> payload;
    private Map<String, Object> metadata;
    private Long sequenceNumber;
    private LocalDateTime timestamp;
    private LocalDateTime createdAt;
}
