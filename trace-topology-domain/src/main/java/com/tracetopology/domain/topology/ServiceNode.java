package com.tracetopology.domain.topology;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ServiceNode {

    private String id;
    private String serviceName;
    private String serviceType;
    private String namespace;
    private String version;
    private Map<String, String> metadata;
    private Instant registeredAt;
    private Instant lastHeartbeatAt;
    private boolean active;

    public void heartbeat() {
        this.lastHeartbeatAt = Instant.now();
        this.active = true;
    }

    public void markInactive() {
        this.active = false;
    }
}
