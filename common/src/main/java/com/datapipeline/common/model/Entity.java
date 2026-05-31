package com.datapipeline.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Entity {

    private String id;
    private String type;
    private String status;
    @Builder.Default
    private Map<String, Object> attributes = new HashMap<>();
    private Instant createdAt;
    private Instant updatedAt;

}
