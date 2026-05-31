package com.logmanager.domain.model;

import lombok.Data;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
public abstract class BaseEntity {
    protected String id;
    protected Instant createdAt;
    protected Instant updatedAt;
    protected Map<String, Object> attributes = new HashMap<>();
}
