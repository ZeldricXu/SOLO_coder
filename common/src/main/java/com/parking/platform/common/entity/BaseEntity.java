package com.parking.platform.common.entity;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public abstract class BaseEntity {

    protected String id;
    protected Instant createdAt;
    protected Instant updatedAt;

    public BaseEntity() {
        this.id = generateId();
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    protected String generateId() {
        String prefix = getIdPrefix();
        return prefix + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    protected abstract String getIdPrefix();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new HashMap<>();
        map.put("id", id);
        map.put("created_at", createdAt);
        map.put("updated_at", updatedAt);
        return map;
    }
}
