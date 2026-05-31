package com.tracetopology.domain.entity;

import com.tracetopology.common.utils.IdGenerator;
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
public class Config {

    private String configId;
    private String namespace;
    private int version;
    private Map<String, Object> parameters;
    private boolean enabled;
    private Instant appliedAt;

    public static Config create(String namespace, Map<String, Object> parameters) {
        return Config.builder()
                .configId(IdGenerator.generateId("cfg"))
                .namespace(namespace)
                .version(1)
                .parameters(parameters)
                .enabled(true)
                .appliedAt(Instant.now())
                .build();
    }

    public Config incrementVersion(Map<String, Object> newParameters) {
        return Config.builder()
                .configId(this.configId)
                .namespace(this.namespace)
                .version(this.version + 1)
                .parameters(newParameters)
                .enabled(this.enabled)
                .appliedAt(Instant.now())
                .build();
    }

    public void toggleEnabled() {
        this.enabled = !this.enabled;
        this.appliedAt = Instant.now();
    }
}
