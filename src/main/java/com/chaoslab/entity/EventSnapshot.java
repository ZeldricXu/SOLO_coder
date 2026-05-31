package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = false)
@TableName("event_snapshot")
public class EventSnapshot {

    private Long id;
    private String snapshotId;
    private String aggregateId;
    private String aggregateType;
    private Map<String, Object> state;
    private Long sequenceNumber;
    private Integer version;
    private LocalDateTime createdAt;
}
