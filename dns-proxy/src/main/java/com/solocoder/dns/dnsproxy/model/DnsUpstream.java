package com.solocoder.dns.dnsproxy.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class DnsUpstream implements Serializable {
    private String id;
    private String name;
    private String host;
    private Integer port;
    private Integer priority;
    private Integer weight;
    private String protocol;
    private Boolean enabled;
    private Integer timeoutMs;
    private Integer maxRetries;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
