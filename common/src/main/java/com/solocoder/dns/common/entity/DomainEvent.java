package com.solocoder.dns.common.entity;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
public class DomainEvent implements Serializable {
    private String eventId;
    private String aggregateId;
    private String eventType;
    private Map<String, Object> payload;
    private Long sequence;
    private LocalDateTime occurredAt;
    private String metadata;
}
