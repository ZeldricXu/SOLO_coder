package com.solocoder.dns.persistence.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("request_trace")
public class RequestTracePO {
    @TableId(type = IdType.INPUT)
    private String traceId;
    private String parentSpanId;
    private String spanId;
    private String serviceName;
    private String operation;
    private LocalDateTime startTime;
    private Long durationMs;
    private Integer statusCode;
    private String errorMessage;
    private String tags;
}
