package com.enterprise.risk.common.event;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RiskEvent implements Serializable {

    @JsonProperty("event_id")
    @Builder.Default
    private String eventId = UUID.randomUUID().toString();

    @JsonProperty("event_type")
    @NotBlank(message = "event_type is required")
    private String eventType;

    @JsonProperty("business_line")
    @NotBlank(message = "business_line is required")
    private String businessLine;

    @JsonProperty("timestamp")
    @NotNull(message = "timestamp is required")
    @Builder.Default
    private Long timestamp = Instant.now().toEpochMilli();

    @JsonProperty("entity_id")
    @NotBlank(message = "entity_id is required")
    private String entityId;

    @JsonProperty("entity_type")
    @NotBlank(message = "entity_type is required")
    private String entityType;

    @JsonProperty("source")
    private String source;

    @JsonProperty("session_id")
    private String sessionId;

    @JsonProperty("ip")
    private String ip;

    @JsonProperty("user_id")
    private String userId;

    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();

    @JsonAnyGetter
    public Map<String, Object> getAttributes() {
        return attributes;
    }

    @JsonAnySetter
    public void setAttribute(String key, Object value) {
        this.attributes.put(key, value);
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public <T> T get(String key) {
        return (T) attributes.get(key);
    }

    @JsonIgnore
    @SuppressWarnings("unchecked")
    public <T> T getOrDefault(String key, T defaultValue) {
        return (T) attributes.getOrDefault(key, defaultValue);
    }

    @JsonIgnore
    public Instant getTimestampAsInstant() {
        return Instant.ofEpochMilli(timestamp);
    }
}
