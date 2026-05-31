package com.chaoslab.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("dns_async_task")
public class DnsAsyncTask extends BaseEntity {

    private String taskId;
    private String domain;
    private String queryType;
    private String status;
    private String priority;
    private String callbackType;
    private String callbackUrl;
    private Map<String, Object> callbackHeaders;
    private String eventName;
    private Map<String, Object> eventPayload;
    private String requestId;
    private String upstreamId;
    private Map<String, Object> result;
    private String errorMessage;
    private Integer retryCount;
    private Integer maxRetries;
    private LocalDateTime submittedAt;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Long durationMs;
    private String requestedBy;
    private Map<String, Object> context;
}
