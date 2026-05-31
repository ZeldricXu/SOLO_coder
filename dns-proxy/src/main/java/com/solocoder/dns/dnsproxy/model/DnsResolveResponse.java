package com.solocoder.dns.dnsproxy.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class DnsResolveResponse implements Serializable {
    private String domain;
    private Integer recordType;
    private List<String> records;
    private Long ttl;
    private Boolean fromCache;
    private String upstreamUsed;
    private Long resolveTimeMs;
    private LocalDateTime resolvedAt;
}
