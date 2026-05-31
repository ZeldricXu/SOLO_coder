package com.solo.config.module.dns.plugin;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Data
public class DnsResolutionContext {

    private String domain;
    private String recordType;
    private LocalDateTime startTime;
    private List<String> resolvedIps = new ArrayList<>();
    private Map<String, Object> attributes = new HashMap<>();
    private boolean resolved = false;
    private String resolvedBy;
    private long resolveTimeMs;

    public DnsResolutionContext(String domain, String recordType) {
        this.domain = domain;
        this.recordType = recordType;
        this.startTime = LocalDateTime.now();
    }

    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }
}
