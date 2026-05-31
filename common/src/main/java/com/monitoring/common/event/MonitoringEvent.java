package com.monitoring.common.event;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MonitoringEvent {

    private String eventId;

    private String eventType;

    private String source;

    private Map<String, Object> payload;

    private Instant timestamp;

    private String traceId;
}
