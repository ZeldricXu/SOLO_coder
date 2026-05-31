package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("event_projection")
public class EventProjection extends BaseEntity {

    private String projectionId;
    private String name;
    private String aggregateType;
    private Map<String, Object> handlerConfig;
    private Long lastSequence;
    private String status;
    private Boolean rebuildInProgress;
}
