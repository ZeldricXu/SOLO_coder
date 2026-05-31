package com.monitoring.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("trace_spans")
public class TraceSpanDO {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String traceId;

    private String spanId;

    private String parentSpanId;

    private String serviceName;

    private String operationName;

    private Long durationNanos;

    private Instant startTime;

    private String tags;

    private String logs;

    private Boolean sampled;

    private Instant createdAt;
}
