package com.datastandard.modules.gateway.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("gateway_access_logs")
public class GatewayAccessLog {

    @TableId(type = IdType.ASSIGN_UUID)
    private String logId;

    private String requestId;

    private String traceId;

    private String spanId;

    private String clientIp;

    private String userId;

    private String method;

    private String path;

    private String queryString;

    private String requestHeaders;

    private String requestBody;

    private Integer statusCode;

    private String responseHeaders;

    private String responseBody;

    private long durationMs;

    private String errorMessage;

    private String upstreamHost;

    private String userAgent;

    private String referer;

    private Instant requestTime;

    private Instant responseTime;

    private Instant createdAt;

    private Integer deleted;
}
