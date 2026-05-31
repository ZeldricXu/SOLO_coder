package com.solocoder.platform.featurestore.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FeatureDefinition implements Serializable {

    private static final long serialVersionUID = 1L;

    private String featureId;
    private String name;
    private String description;
    private FeatureType type;
    private String owner;
    private String version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Map<String, String> tags;

    public enum FeatureType {
        NUMERIC, CATEGORICAL, EMBEDDING, BOOLEAN, TIMESTAMP
    }
}
