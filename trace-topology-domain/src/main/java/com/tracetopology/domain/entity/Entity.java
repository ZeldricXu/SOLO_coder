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
public class Entity {

    private String id;
    private String type;
    private String status;
    private Map<String, Object> attributes;
    private Instant createdAt;
    private Instant updatedAt;

    public static Entity create(String type, Map<String, Object> attributes) {
        return Entity.builder()
                .id(IdGenerator.generateId("ent"))
                .type(type)
                .status("created")
                .attributes(attributes)
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();
    }

    public void updateStatus(String status) {
        this.status = status;
        this.updatedAt = Instant.now();
    }

    public void updateAttributes(Map<String, Object> attributes) {
        this.attributes = attributes;
        this.updatedAt = Instant.now();
    }
}
