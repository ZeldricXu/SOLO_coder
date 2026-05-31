package com.chaoslab.modules.dns.dto;

import lombok.Data;

import java.util.Map;

@Data
public class AsyncDnsResolveRequest {
    private String domain;
    private String queryType = "A";
    private Boolean forceRefresh = false;
    private String priority = "normal";
    private String callbackType;
    private String callbackUrl;
    private Map<String, Object> callbackHeaders;
    private String eventName;
    private Map<String, Object> eventPayload;
    private String requestedBy;
    private Map<String, Object> context;
    private Integer maxRetries = 3;
    private Long timeoutMs = 5000L;
}
