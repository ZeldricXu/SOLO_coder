package com.nftindexer.event;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DomainEvent implements Serializable {

    private String eventId;
    private String eventType;
    private String aggregateId;
    private String aggregateType;
    private Map<String, Object> payload;
    private Map<String, Object> metadata;
    private LocalDateTime timestamp;
    private String traceId;
}
