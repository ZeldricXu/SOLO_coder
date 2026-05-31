package com.device.platform.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.Instant;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("request_log")
public class RequestLog extends BaseEntity {
    private String traceId;
    private String spanId;
    private String parentSpanId;
    private String serviceName;
    private String requestMethod;
    private String requestPath;
    private Integer responseStatus;
    private Long durationMs;
    private String clientIp;
    private String userAgent;
    private String requestHeaders;
    private String requestBody;
    private String responseBody;
    private String errorMessage;
    private Instant startTime;
    private Instant endTime;
}
