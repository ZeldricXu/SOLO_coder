package com.monitoring.trace.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.experimental.SuperBuilder;

import java.time.Instant;
import java.util.Map;

@Data
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class TraceSpan {

    private String traceId;

    private String spanId;

    private String parentSpanId;

    private String serviceName;

    private String operationName;

    private Long durationNanos;

    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ss'Z'", timezone = "UTC")
    private Instant startTime;

    private Map<String, String> tags;

    private Map<String, Object> logs;

    private Boolean error;

    private Integer httpStatus;
}
