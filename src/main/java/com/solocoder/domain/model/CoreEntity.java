package com.solocoder.domain.model;

import lombok.Data;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.Instant;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CoreEntity {
    private String id;
    private String type;
    private String status;
    private Map<String, Object> attributes;
    private Instant createdAt;
    private Instant updatedAt;
}
