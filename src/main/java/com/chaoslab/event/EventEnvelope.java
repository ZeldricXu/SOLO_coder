package com.chaoslab.event;

import com.chaoslab.common.JsonUtils;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class EventEnvelope {
    private String eventId;
    private String eventType;
    private Integer eventVersion;
    private String aggregateId;
    private String aggregateType;
    private String payloadJson;
    private String metadataJson;
    private Long sequenceNumber;
    private LocalDateTime timestamp;

    public static <T> EventEnvelope from(DomainEvent<T> event, Long sequenceNumber) {
        EventEnvelope envelope = new EventEnvelope();
        envelope.setEventId(event.getEventId());
        envelope.setEventType(event.getEventType());
        envelope.setEventVersion(event.getEventVersion());
        envelope.setAggregateId(event.getAggregateId());
        envelope.setAggregateType(event.getAggregateType());
        envelope.setPayloadJson(JsonUtils.toJson(event.getPayload()));
        envelope.setMetadataJson(JsonUtils.toJson(event.getMetadata()));
        envelope.setSequenceNumber(sequenceNumber);
        envelope.setTimestamp(event.getTimestamp());
        return envelope;
    }
}
