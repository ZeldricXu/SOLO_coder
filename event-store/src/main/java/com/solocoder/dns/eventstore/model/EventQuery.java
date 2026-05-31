package com.solocoder.dns.eventstore.model;

import lombok.Data;
import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class EventQuery implements Serializable {
    private String eventId;
    private String aggregateId;
    private String eventType;
    private String payload;
    private Long sequence;
    private LocalDateTime occurredAt;
    private String metadata;
}
