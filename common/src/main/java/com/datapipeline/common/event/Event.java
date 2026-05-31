package com.datapipeline.common.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Event {

    @Builder.Default
    private String eventId = UUID.randomUUID().toString();
    private String type;
    private String source;
    @Builder.Default
    private Map<String, Object> payload = new HashMap<>();
    @Builder.Default
    private Instant timestamp = Instant.now();
    private String traceId;

}
