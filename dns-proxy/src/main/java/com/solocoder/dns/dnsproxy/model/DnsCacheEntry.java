package com.solocoder.dns.dnsproxy.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class DnsCacheEntry implements Serializable {
    private String id;
    private String domain;
    private Integer recordType;
    private String recordData;
    private Long ttl;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private Integer hitCount;
}
