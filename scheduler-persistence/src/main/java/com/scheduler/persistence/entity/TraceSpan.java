package com.scheduler.persistence.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.scheduler.common.model.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("trace_spans")
public class TraceSpan extends BaseEntity {
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String serviceName;
    private String operationName;
    private Instant startTime;
    private Instant endTime;
    private Long durationMicros;
    private String status;
    private Map<String, Object> tags;
    private Map<String, String> baggage;
    private String kind;
    private String host;
    private String processId;
    private Boolean sampled;
}
