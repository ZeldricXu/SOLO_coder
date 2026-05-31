package com.chaoslab.modules.dns.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class DnsResolveResponse {

    private String domain;
    private String queryType;
    private List<String> answers;
    private long ttl;
    private boolean fromCache;
    private String upstreamId;
    private Map<String, Object> additional;
    private LocalDateTime resolvedAt;
}
