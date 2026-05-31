package com.datastandard.common.model;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.datastandard.common.handler.JsonMapTypeHandler;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "gateway_access_logs", autoResultMap = true)
public class GatewayAccessLog {

    @TableId(type = IdType.INPUT)
    @TableField("log_id")
    private String logId;

    @TableField("trace_id")
    private String traceId;

    @TableField("request_path")
    private String requestPath;

    @TableField("request_method")
    private String requestMethod;

    @TableField(value = "request_headers", typeHandler = JsonMapTypeHandler.class)
    private Map<String, Object> requestHeaders;

    @TableField("request_body")
    private String requestBody;

    @TableField("response_status")
    private Integer responseStatus;

    @TableField(value = "response_headers", typeHandler = JsonMapTypeHandler.class)
    private Map<String, Object> responseHeaders;

    @TableField("response_body")
    private String responseBody;

    @TableField("client_ip")
    private String clientIp;

    @TableField("service_name")
    private String serviceName;

    @TableField("latency_ms")
    private Long latencyMs;

    @TableField("error_message")
    private String errorMessage;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
