package com.meshcontrol.eventstore.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.HashMap;
import java.util.Map;

@Data
public class EventPublishRequest {

    @NotBlank(message = "aggregateId is required")
    @Size(max = 128, message = "aggregateId exceeds maximum length of 128")
    private String aggregateId;

    @NotBlank(message = "aggregateType is required")
    @Size(max = 64, message = "aggregateType exceeds maximum length of 64")
    private String aggregateType;

    @NotBlank(message = "eventType is required")
    @Size(max = 64, message = "eventType exceeds maximum length of 64")
    private String eventType;

    @Size(max = 10000, message = "payload exceeds maximum size of 10KB")
    private Map<String, Object> payload = new HashMap<>();

    @Size(max = 2000, message = "metadata exceeds maximum size of 2KB")
    private Map<String, Object> metadata;

    @Size(max = 64, message = "source exceeds maximum length of 64")
    private String source;
}
