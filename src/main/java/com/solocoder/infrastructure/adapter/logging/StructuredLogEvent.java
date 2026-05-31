package com.solocoder.infrastructure.adapter.logging;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
class StructuredLogEvent {
    @Builder.Default
    private String eventId = UUID.randomUUID().toString();
    @Builder.Default
    private Instant timestamp = Instant.now();
    private String level;
    private String message;
    private String loggerName;
    private String threadName;
    private Map<String, Object> context;
    private String stackTrace;
}
